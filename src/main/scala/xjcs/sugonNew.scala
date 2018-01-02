package xjcs

import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.SparkConf
import org.apache.spark.streaming.kafka010.ConsumerStrategies._
import org.apache.spark.streaming.kafka010.KafkaUtils
import org.apache.spark.streaming.kafka010.LocationStrategies._
import org.apache.spark.streaming.{Seconds, StreamingContext}
import play.api.libs.json.{JsValue, Json}

import scala.collection.JavaConversions._
import org.apache.spark.storage.StorageLevel
import org.elasticsearch.spark._
import org.apache.spark.rdd.UnionRDD

//import scala.collection.mutable.ListBuffer

//import Following._


object sugonNew {
  def main(args: Array[String]): Unit = {
    val Array(timeMargin, topic, partitionNum) = args
    //val topic = Array("test1")
    val conf = new SparkConf()
      .setAppName("My App")
      .set("es.index.auto.create", "true")
      .set("es.nodes", "21.36.128.64")
      .set("es.port", "9200")
      .set("es.query", "?q=me*")
      .set("spark.driver.allowMultipleContexts", "true")

    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> "node2:9092",
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "example",
      "auto.offset.reset" -> "latest"
    )
    val sc = new StreamingContext(conf, Seconds(timeMargin.toInt))
    KafkaUtils.createDirectStream[String, String](sc, PreferConsistent, Subscribe[String, String](Array(topic), kafkaParams))
      .map(record => record.value)
      .map[JsValue](Json.parse)
      .map { json =>
        val camera_id = (json \ "camera_id").as[String]
        val track_id = (json \ "track_id").as[Int].toString
        val track_type = (json \ "track_type").as[Int].toString
        val company = (json \ "company").as[String]
        val str_id = camera_id + "_" + track_id + "_" + track_type + "_" + company
        (str_id, json)
      }
      .foreachRDD { rdd =>
        if (!rdd.isEmpty) {
          println("rdd partition num is ", rdd.getNumPartitions)
//          val jedisOuter = JedisClient.getJedisCluster()
          val scc = rdd.sparkContext
          //        val initialList=scala.collection.mutable.ListBuffer.empty[JsValue]
          //        val addToList = (s:scala.collection.mutable.ListBuffer[JsValue],v:JsValue) => s += v
          //        val mergePartitionSets=(p1:scala.collection.mutable.ListBuffer[JsValue],p2:scala.collection.mutable.ListBuffer[JsValue])=> p1 ++= p2
          //        val newRdd = rdd.aggregateByKey(initialList)(addToList,mergePartitionSets).persist(StorageLevel.MEMORY_AND_DISK)
          val newRdd = rdd.groupByKey(partitionNum.toInt).persist(StorageLevel.MEMORY_ONLY)
          data2redis(newRdd)
          newRdd.map { case (str_id, coll) =>
            val track_type = (coll.head \ "track_type").as[Int].toString
            (str_id, track_type)
          }.mapPartitions {eachPartition: Iterator[(String, String)] =>
            val jedisInner = JedisClient.getJedisCluster()
            val newPartition = eachPartition.map { case (ele1,_) =>
              val temp1 = jedisInner.zrange(ele1, 0, -1).toSeq
              val start_time = jedisInner.zrangeWithScores(ele1, 0, -1).headOption match {
                case Some(head) => head.getScore
                case None => 0.toDouble
              }
              val screenShotPath = extractField(Json.parse(temp1.apply(temp1.size / 2)), "screenshot_path")
              checkFieldAtcameraGis(getLastFeature(temp1) ++ Map("doc_id" -> ele1) ++ Map("start_time" -> start_time) ++ Map("screenshot_path" -> screenShotPath))
            }.toList
            jedisInner.close()
            newPartition.iterator
          }.saveToEs("attributetemp/realtime", Map("es.mapping.id" -> "doc_id"))
          //  next is about history tables
          val beforeTime = computeEarlyTime(newRdd)
          val bT = scc.broadcast(beforeTime)
           val rddUnion=newRdd.map(_._1.split("_")(0) + "*").collect().map { cameraId =>
             val jedisOuter = JedisClient.getJedisCluster()
             val keyArray=clusterKeys(jedisOuter, cameraId).toSeq
             jedisOuter.close()
            val rdd1 = scc.parallelize(keyArray, 20).mapPartitions { eachPartition:Iterator[String] =>
              val jedisInner2 = JedisClient.getJedisCluster()
              val parIter=eachPartition.map{key=>
                val beforeTime = bT.value
                val historyLastTime = jedisInner2.zrangeWithScores(key, 0, -1).last.getScore
                if (!computeTime(beforeTime, historyLastTime, 100)) {
                  val data = jedisInner2.zrange(key, 0, -1).toSeq
                  val historyFirstTime = jedisInner2.zrangeWithScores(key, 0, -1).head.getScore
                  val timeMapTuple = Map("start_time" -> historyFirstTime, "end_time" -> historyLastTime)
                  val position = computePosition(key, jedisInner2)
                  val screenShotPath = extractField(Json.parse(data.apply(data.size / 2)), "screenshot_path")
                  (checkFieldAtcameraGis(getLastFeature(data) ++ timeMapTuple ++ position ++ Map("doc_id" -> key) ++ Map("screenshot_path" -> screenShotPath)), key)

                }
                else (Map.empty[String,Any],null)
              }.toList
              jedisInner2.close()
              parIter.iterator
              }
             rdd1.filter(_._2!=null)
//            rdd1.map(_._1).saveToEs("attribute/history", Map("es.mapping.id" -> "doc_id"))
//            rdd1.map(_._2).collect().foreach(jedisInner2.del)
//            jedisInner2.close()
          }
          val finalRdd = new UnionRDD(scc,rddUnion.toSeq).persist(StorageLevel.MEMORY_ONLY)
          finalRdd.map(_._1).saveToEs("attribute/history", Map("es.mapping.id" -> "doc_id"))
          val jedisOuter = JedisClient.getJedisCluster()
          finalRdd.map(_._2).collect().foreach(jedisOuter.del)
          jedisOuter.close()
        }
        else println("rdd is empty !")

      }
    sc.start()
    sc.awaitTermination()

  }
}
