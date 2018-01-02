
/*
package xjcs

/**
 * Created by zhangxinyi on 17/5/10.
 */
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import org.apache.spark.SparkConf
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.kafka.KafkaUtils
import org.apache.spark.streaming.{Seconds, StreamingContext}
import play.api.libs.json.{JsValue, Json}
import org.elasticsearch.spark._
import redis.clients.jedis.JedisPool
import scala.collection.JavaConversions._

object DataPipeLine {



  // kafka
  // groupid
  // topic与logstash
  /**
   * TODO
   * zookeeper
   */
  val numThreads = 1
  val zookeeperQuorum = "node1:2181,node2:2181,node3:2181"
  val groupId = "test"
  val topic = Array("logstash1").map((_, numThreads)).toMap


  // inde
  // es.index.auto.cre
  val elasticResource = "hellokitty/log"

  /**
   * TODO
   *
   */

  val conf = new SparkConf()
    .setMaster("local[*]")
    .setAppName("My App")
    .set("es.index.auto.create", "true")

  val sc = new StreamingContext(conf, Seconds(10))
  val scc = sc.sparkContext
  /**
   * TODO
   * checkpoint
   */
  // sc.checkpoint("checkpoint")

  val funcs = new UtilTool

  def main(args: Array[String]): Unit = {

    // 接收kafka data stream
    val logs = KafkaUtils.createStream(sc,
      zookeeperQuorum,
      groupId,
      topic,
      StorageLevel.MEMORY_AND_DISK_SER)
      .map(_._2.toString)

      logs.print()

    val jsonLines = logs.map[JsValue](l => Json.parse(l))
    val Idjson = jsonLines.map[(String, (String,Double))](json => {
            val cameraId = (json \ "camera_id").as[String]
            val trackId = (json \ "track_id").as[Int].toString
            val Id = cameraId+"_"+trackId
            val track_xmax = (json \ "track_xmax").as[Int]
            val track_ymax = (json \ "track_ymax").as[Int]
            val track_xmin = (json \ "track_xmin").as[Int]
            val timestamp = (json \ "timestamp").as[String].filter(!":- ".contains(_)).toDouble
            val track_ymin = (json \ "track_ymin").as[Int]
            val subjson = Json.obj(
                "xmax" -> track_xmax,
                "xmin" -> track_xmin,
                "ymax" -> track_ymax,
                "ymin" -> track_ymin,
                "timestamp" -> timestamp
            )
            (Id, (subjson.toString(),timestamp))

        })
    Idjson.print()


    val initialSet = collection.mutable.HashSet.empty[(String,Double)]
    val addToSet = (s: collection.mutable.HashSet[(String,Double)], v: (String,Double)) => s += v
    val mergePartitionSets = (p1: collection.mutable.HashSet[(String,Double)], p2: collection.mutable.HashSet[(String,Double)]) => p1 ++= p2


    Idjson.foreachRDD{ rdd => {


      // 用aggregateByKey将RDD中相同key的value组成Set集合，即把tuple(Value,Score)放入HashSet中
      // rdd 中数据流格式=> (key:String,(Value:Stiring,Timestamp:Double))
      // aggreagtebykey之后：result 中数据流格式=> (key:String,HashSet[Value:String,Timestamp:Double])
      val result = rdd.aggregateByKey(initialSet)(addToSet, mergePartitionSets).collect()

      result.foreach({case (k, v) => {

        val jedis = RedisClient.pool.getResource


        // 先把新数据写入到redis中，按照timestamp进行排序，再把全部历史数据提出，进行预警的判断
        // 写入redis过程中，格式转化成jedis client所需的java Collection
        // 用zadd方法添加Set({value,timestamp}),可以在set内部按照timestamp排序（timestamp需为Double类型）
        // 应在集群配置中设置过期时间，过期之后自动删除

        val valuescore = mapAsJavaMap(v.toMap).asInstanceOf[java.util.Map[java.lang.String, java.lang.Double]]
        jedis.zadd(k,valuescore)
        // jedis.expire(k,900)
        val history = jedis.zrange(k, 0, -1).toList
        // 异常行为判断，如果产生异常行为数据，则传入到ES中，并设置es中的唯一id，可以根据相同id覆盖异常行为的时长。
        val wandering = funcs.wandering_detect(k,history)
        if(wandering.nonEmpty){
          scc.makeRDD(Seq(wandering.get)).saveToEs(elasticResource,Map("es.mapping.id"->"id"))
          println("successfully sent to ES")

        }


      }})}}

    sc.start()
    sc.awaitTermination()
  }
}

/**
 * TODO
 * redis连接设置
 */
object RedisClient extends Serializable {
  val redisHost = "19.19.19.42"
  val redisPort = 6379
  val redisTimeout = 30000
  lazy val pool = new JedisPool(new GenericObjectPoolConfig(), redisHost, redisPort, redisTimeout)

  lazy val hook = new Thread {
    override def run = {
      println("Execute hook thread: " + this)
      pool.destroy()

    }
  }
  sys.addShutdownHook(hook.run)
}



**
*/