/*
package xjcs

/**
 * Created by zhangxinyi on 17/5/16.
 */

import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.SparkConf
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.spark.streaming.kafka010.KafkaUtils
//import org.apache.spark.streaming.kafka._

import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.{Seconds, StreamingContext}
import play.api.libs.json.{JsNull, JsValue, Json}

object Multiple_camera_detect {


  //    val numThreads = 1
  //    val zookeeperQuorum = "localhost:2181"
  //    val groupId = "test"
  //    val topic = Array("logstash").map((_, numThreads)).toMap
  val topic = Array("logstash")
  val topicsSet = Set("logstash")
  //    val elasticResource = "abnormal/log"


  val conf = new SparkConf()
    .setMaster("local[*]")
    .setAppName("My App")
    .set("es.index.auto.create", "true")
    .set("es.nodes", "10.0.37.205")
    .set("es.port","9200")
    .set("es.query", "?q=me*")


  val sc = new StreamingContext(conf, Seconds(10))
  val scc = sc.sparkContext

  //    sc.checkpoint("checkpoint")

  val funcs = new UtilTool

  def main(args: Array[String]): Unit = {


    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> "localhost:9092",
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "example",
      "auto.offset.reset" -> "latest"
//      "enable.auto.commit" -> (false: java.lang.Boolean)
    )


    val logs = KafkaUtils.createDirectStream[String,String,StringDeserializer,StringDeserializer,long](sc,kafkaParams,"logstash").map(record=>record.value)

    val jsonLines = logs.map[JsValue](l => Json.parse(l))
    //    jsonLines.cache()

    val IdJson = jsonLines.map[(String,(String,Double))]{
      json =>{
      // 改结构（ID，(location_array|feature_array|geo_array,timestamp)）
        val cameraId = (json \ "camera_id").as[String]
        val trackId = (json \ "track_id").as[Int].toString
        val object_type = (json \ "track_type").as[Int]
        val track_xmax = (json \ "track_xmax").as[Int]
        val track_ymax = (json \ "track_ymax").as[Int]
        val track_xmin = (json \ "track_xmin").as[Int]
        val track_ymin = (json \ "track_ymin").as[Int]
        val lat = (json \ "camera_gis" \"lat").as[Double]
        val lon = (json \ "camera_gis" \"lon").as[Double]
        val track_location = collection.mutable.ArrayBuffer[Int]()
        track_location += track_xmax
        track_location += track_xmin
        track_location += track_ymax
        track_location += track_ymin
        val camera_gis = collection.mutable.ArrayBuffer[Double](lat,lon)
        val timestamp = (json \ "timestamp").as[String].filter(!":- ".contains(_)).toDouble

        if (object_type == 0){
          val str_Id = "p_"+cameraId+"_"+trackId
          val features = collection.mutable.ArrayBuffer[Double]()
          for (a <- 1 to 1024){
            val flag:Double = json \ ("p_feature_"+a)
            if (flag.getOrElse(JsNull)!=JsNull){
                features += flag
            }
            }
          (str_Id,(collection.mutable.ArrayBuffer[Any](track_location,features,camera_gis).toString,timestamp))
        }
        else{
          val str_Id = "mv_"+cameraId+"_"+trackId
          val features = collection.mutable.ArrayBuffer[Double]()
          for (a <- 1 to 1024){
            val flag:Double = json \ ("mv_feature_"+a)
            if (flag.getOrElse(JsNull)!=JsNull){
                features += flag
            }
            }
//          (str_Id,(track_location.toString+"#"+features.toString+"#"+camera_gis.toString,timestamp))
            (str_Id,(collection.mutable.ArrayBuffer[Any](track_location,features,camera_gis).toString,timestamp))
        }

      }
      }

    val IdJson_p = IdJson.filter{case (k,v)=>k.contains("p_")}

    val initialSet = collection.mutable.ArrayBuffer.empty[(String,Double)]
    val addToSet = (s: collection.mutable.ArrayBuffer[(String,Double)], v: (String,Double)) => s += v
    //    val addToSet = (s: collection.mutable.ArrayBuffer[(String,Double)], v: (String,Double)) => {
    //      val values = v._1.split("|")
    //      val location = values(0)
    //      val features = values(1)
    //      val gis = values(2)
    //    }
    val mergePartitionSets = (p1: collection.mutable.ArrayBuffer[(String,Double)], p2: collection.mutable.ArrayBuffer[(String,Double)]) => p1 ++= p2


    IdJson_p.foreachRDD{
      rdd=>{
        val result = rdd.aggregateByKey(initialSet)(addToSet, mergePartitionSets).collect()
        result.foreach{
          case (k,v)=>{
            val k_array = k.split("_")
            val camera_id = k_array(1)
            val track_id = k_array(2)
            println(v)
            println(v.getClass)
            val jedis = RedisClient.pool.getResource
            //
            val history = jedis.zrange("path_"+k, 0, -1)
            if (history.isEmpty){
              //
              //
              //              val value = mapAsJavaMap(v.toMap).asInstanceOf[java.util.Map[java.lang.String, java.lang.Double]]
              //              jedis.zadd("path_"+k,value)
              //              val starttime = v.head._2

              /**
               * 在es中查找 geo<500m,time<15s,track_type=0,camera_id != xx的记录
               */
              //              val queryStr = """{"query":{"match":{"activity.partnerCode": "abc"}}}"""
              //              val docs = EsSpark.esRDD(scc,"ks/log",queryStr).collect()




              /**
               * 
               */
            }

          }
        }
      }
    }

    sc.start()
    sc.awaitTermination()
  }

}


//object RedisClient extends Serializable {
//  val redisHost = "localhost"
//  val redisPort = 6379
//  val redisTimeout = 30000
//  lazy val pool = new JedisPool(new GenericObjectPoolConfig(), redisHost, redisPort, redisTimeout)
//
//  lazy val hook = new Thread {
//    override def run = {
//      println("Execute hook thread: " + this)
//      pool.destroy()
//    }
//  }
//  sys.addShutdownHook(hook.run)
//}

*
*/