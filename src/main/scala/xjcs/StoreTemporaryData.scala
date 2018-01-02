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
import following2._
//import SuspeciousDetect
//import org.apache.spark.rdd.RDD

//import scala.collection.mutable.ListBuffer

//import Following._


/**
  * @author fanweiming on 2017/7/20 in sugon
  */

object StoreTemporaryData {

  def main(args: Array[String]): Unit = {
    val Array(timeMargin,topic,partitionNum,single_wandering_duration,single_speed_lower,single_speed_higher,single_wandering_entropy, single_night_limit)=args
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
    val logs=KafkaUtils.createDirectStream[String, String](sc, PreferConsistent, Subscribe[String, String](Array(topic), kafkaParams))
        .map(record => record.value)
        .map[JsValue](Json.parse)
        .map { json =>
      val camera_id = (json \ "camera_id").as[String]
      val track_id = (json \ "track_id").as[Int].toString
      val track_type = (json \ "track_type").as[Int].toString
      val company=(json \ "company").as[String]
      val str_id = camera_id + "_" + track_id + "_" + track_type + "_" + company
      (str_id, json)}

      logs.foreachRDD { rdd =>
      if (!rdd.isEmpty) {
        val scc = rdd.sparkContext
        val newRdd=rdd.groupByKey(50).persist(StorageLevel.MEMORY_ONLY)
        val time1=System.currentTimeMillis() / 1000
        data2redis(newRdd)
        val time2=System.currentTimeMillis() / 1000
        println("process of data to redis consume ",time2-time1)
        val beforeTime = computeEarlyTime(newRdd)
        val jedisOuter = JedisClient.getJedisCluster()

        val extract_time=getMiddleTime(beforeTime,20)
        val rdd2=getNewRdd(extract_time,rdd).flatMap{case (camera_id,list1)=>
        val matrix1=getMatrix(list1)
            if(isFollowing(matrix1,2,1,3)._1) Some((camera_id,isFollowing(matrix1,2,1,3)._2))
            else None
        }
//        val jedisOuter = JedisClient.getJedisCluster()
        if(!rdd2.isEmpty()){
          rdd2.collect().foreach{case (ele:String,point:position_point)=>
            val newKeyMark=ele + "*"
            val historyKeys = clusterKeys(jedisOuter,newKeyMark) //某一个摄像头下面的所有的keys
            val dataTosave=historyKeys.flatMap{t=>
//              dataTosave
              val keys = jedisOuter.zrangeByScore(t,extract_time,extract_time).filter(_.contains("\"track_type\":1"))
              if(keys.nonEmpty){
                val dataMap=keys.filter{jString=>
                  val x1_grid:Double=extractField(Json.parse(jString),"track_xmax").toDouble
                  val x2_grid:Double=extractField(Json.parse(jString),"track_xmin").toDouble
                  val x_grid=(x1_grid + x2_grid) / 2.0
                  point.a==x_grid
                }.map(Json.parse(_).toMap)
                Some(dataMap.head ++ Map("type"->"following")
                  ++ Map("start_time"->(extract_time-10)) ++ Map("end_time"->(extract_time+10)))
              }
              else None
            }
            try{
            scc.makeRDD(dataTosave.toSeq,partitionNum.toInt).saveToEs("abnormal/realtime")
            println("following people exits !")}
            catch{case _:Exception=>"data writing process is wrong !"}
          }
        }


        val idAndType = computeEachIdAndType(newRdd)
        val idCollection=idAndType.map(_._1)
//        val jedisOuter = JedisClient.getJedisCluster()
        val temporaryData=idAndType.map{case (id:String,track_type:String)=>
          val tempData=jedisOuter.zrange(id,0,-1).toSeq
          val endTime = jedisOuter.zrangeWithScores(id, 0, -1).last.getScore
          val start_time = jedisOuter.zrangeWithScores(id,0,-1).headOption match {
          case Some(head)=>head.getScore
            case None=>0.toDouble
          }
          val company = id.split("_").last
          if (track_type == "1") {
            //                println("single camera detecting")
            val wandering = SuspeciousDetect.wandering_highspeed_night_detect(id, tempData.toList, single_wandering_duration.toInt, single_speed_lower.toFloat, single_speed_higher.toFloat, single_wandering_entropy.toFloat, single_night_limit.toInt)
            if (wandering.nonEmpty) {
              scc.makeRDD(Seq(wandering.get)).saveToEs("abnormal/realtime", Map("es.mapping.id" -> "id"))
              scc.makeRDD(Seq(wandering.get)).saveToEs("abnormal/history", Map("es.mapping.id" -> "id"))
              //              println("successfully sent to ES abnormal")
            }
          }
          if (company=="st"){
            val screenShotPath = extractField(Json.parse(tempData.apply(tempData.size / 2)),"screenshot_path")
            checkFieldAtcameraGis(getLastFeature(tempData) ++ Map("doc_id"->id) ++ Map("start_time"-> start_time) ++Map("end_time"->endTime) ++ Map("screenshot_path"->screenShotPath))
          }
          else  {
            val partCharacter1 = majorityVote(tempData)
            val partConf1 = computeConf(tempData)
            val partOther1 = computeOtherFeature(tempData)
            if (track_type == "1") {
              val feature1 = computeFeature(tempData)
              val res1: Map[String, Any] = partCharacter1 ++ partConf1 ++ partOther1 ++ feature1 ++ Map("doc_id" -> id) ++ Map("start_time" ->start_time)
              res1.toSeq.filter(_._2 != 3.14).toMap
              // scc.makeRDD(Seq(res)).saveToEs("temporary/log",Map("es.mapping.id" -> "id"))
            }
            else {
              val res2: Map[String, Any] = partCharacter1 ++ partConf1 ++ partOther1 ++ Map("doc_id" -> id) ++Map("start_time" -> start_time)
              res2.toSeq.filter(_._2 != 3.14).toMap
              //scc.makeRDD(Seq(res)).saveToEs("temporary/log",Map("es.mapping.id" -> "id"))
            }
          }
        }
        try{
          scc.makeRDD(temporaryData,partitionNum.toInt).saveToEs("attributetemp/realtime",Map("es.mapping.id" -> "doc_id"))
        }catch{
          case _:Exception=>println("data writing process is wrong !")
        }

  //    scc.makeRDD(Abnormal.findAbnormal(idAndType,jedisOuter)).saveToEs("abnormal/realtime",Map("es.mapping.id"->"doc_id"))

        idCollection.map(_.split("_")(0) + "*").toSet.foreach{uniqueId:String=>
          val historyKeys = clusterKeys(jedisOuter,uniqueId) //某一个摄像头下面的所有的keys
          if (historyKeys.nonEmpty) {
//          following2Es(historyKeys.toSeq,jedisOuter,beforeTime,scc)
            val historyData=historyKeys.toSeq.flatMap{ key:String =>        //某一个key
              val track_type1=key.charAt(key.length-1).toString
              val historyLastTime = jedisOuter.zrangeWithScores(key, 0, -1).last.getScore
              //val historyFirstTime = jedisOuter.zrangeWithScores(key,0,-1).head.getScore
              if (!computeTime(beforeTime, historyLastTime, 100)) {
                println("absolutely exists outCamera person!",beforeTime,historyLastTime)
                val data = jedisOuter.zrange(key, 0, -1).toSeq
                //findAbnormal(data.asInstanceOf[Set[String]])
                val partCharacter = majorityVote(data)
                val historyFirstTime = jedisOuter.zrangeWithScores(key,0,-1).head.getScore
                val partConf = computeConf(data)
                val partOther =computeOtherFeature(data)
                val position = computePosition(key, jedisOuter)
                val company = key.split("_").last
                val timeMapTuple = Map("start_time" -> historyFirstTime, "end_time" -> historyLastTime)
                if (company == "st") {
                  val screenShotPath = extractField(Json.parse(data.apply(data.size / 2)),"screenshot_path")
                  Some((checkFieldAtcameraGis(getLastFeature(data) ++ timeMapTuple ++ position ++ Map("doc_id" -> key) ++ Map("screenshot_path"->screenShotPath)),key))
                }
                else {
                if (track_type1 == "1") {
                  val feature = computeFeature(data)
                  val res3: Map[String, Any] = partCharacter ++ partConf ++ partOther ++ timeMapTuple ++ position ++ feature ++ Map("doc_id" -> key)
                  val res4 = res3.toSeq.filter(_._2 != 3.14).toMap
                  Some((res4,key))
                }
                else {
                  val res5: Map[String, Any] = partCharacter ++ partConf ++ partOther ++ timeMapTuple ++ position ++ Map("doc_id" -> key)
                  val res6 = res5.toSeq.filter(_._2 != 3.14).toMap
                  Some((res6,key))
                }
              }

              }
              else None
            }
//            Following.following2Es(historyKeys.toSeq,jedisOuter,beforeTime,scc)
            // store data to history index
            if(historyData.nonEmpty){
              try{
                scc.makeRDD(historyData.map(_._1),partitionNum.toInt).saveToEs("attribute/history",Map("es.mapping.id"->"doc_id"))
                scc.makeRDD(historyData.map(_._1).map(r=>Map("doc_id"->r.getOrElse("doc_id",0))),partitionNum.toInt).saveToEs("attributetemp/realtime",Map("es.mapping.id"->"doc_id"))
              }
              catch{
                case _:Exception=>println("data writing process is wrong !")
              }
            }
//            if(Abnormal.findAbnormalHistory(historyKeys,jedisOuter,beforeTime).nonEmpty){
//              scc.makeRDD(Abnormal.findAbnormalHistory(historyKeys,jedisOuter,beforeTime)).saveToEs("abnormal/history",Map("es.mapping.id"->"doc_id"))
//            }
            historyData.map(_._2).foreach(jedisOuter.del) // del key
        }

        }
        val lastTime = computeLastTime(newRdd)
        SuspeciousDetect.totalHighSpeed(idCollection.map(_.split("_")(0) + "*").toSet,lastTime,beforeTime,scc,jedisOuter)
        jedisOuter.close()
      }
      else println("rdd is empty!")

    }

    sc.start()
    sc.awaitTermination()
  }
}









