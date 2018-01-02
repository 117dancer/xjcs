import java.text.SimpleDateFormat

import breeze.linalg.{DenseMatrix, Vector, norm}
import play.api.libs.json.{JsDefined, JsUndefined, JsValue, Json}
import org.apache.spark.rdd.RDD

import scala.math.abs
import scala.collection.JavaConversions._
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import redis.clients.jedis.{Jedis, JedisCluster}
//import breeze.linalg._

import scala.collection.mutable

//import scala.collection.mutable

//import scala.collection.mutable

//import scala.collection.mutable.Set

/**
  *  @author fanweiming
  *   2017/7/20
  * package object,some functions used in main entry
 */

package object xjcs extends Serializable {
  val BYTE_SIZE = 8
  val BYTE_MASK = 0xFF
  val FLOAT_SIZE = 4
  val DOUBLE_SIZE = 8

  val decoder = new org.apache.commons.codec.binary.Base64(-1, Array(), false)

  def int2float = java.lang.Float.intBitsToFloat _

  def float2int = java.lang.Float.floatToIntBits _

  def long2double = java.lang.Double.longBitsToDouble _

  def double2long = java.lang.Double.doubleToLongBits _

  /**
    *  st and ks commonly used json field names
  * */

  val fieldNames = Array(
    "bag_handbag", "banner", "factor", "fps", "gender", "glass", "upper_color",
    "hat", "height", "logo", "long_hair", "lower_color", "mask", "mv_color", "mv_color_conf", "logo_conf",
    "mv_type", "nv_color", "nv_type", "objnum", "offset", "pants", "pts", "ride", "scale",
    "sleeve", "stripe", "upper_clothing", "upper_color_conf", "weapon", "weapon_conf", "wide",
    "bag_handbag_conf", "gender_conf", "glass_conf", "hat_conf", "long_hair_conf", "banner_conf"
    , "mask_conf", "mv_classification_conf", "mv_type_conf", "nv_type_conf", "pants_conf", "ride_conf"
    , "sleeve_conf", "stripe_conf", "track_conf", "upper_clothing_conf", "lower_color_conf", "nv_color_conf"
  )

  val fieldNamesRemain = Array("url", "feature", "screeenshot_path", "frame_timestamp", "objnum", "track_xmin", "track_xmax", "track_ymin", "track_ymax")

  //val personFieldNames=Array()
  def extractField(data: JsValue, field: String): String = {
    val dd = data \ field match {
      case JsDefined(name) => name
      case JsUndefined() => "no"
    }
    dd.toString.replace("\"", "")
  }

  /*
  def jsonToRdd(fields: Array[(String, Int)], js: JsValue): Map[String, Any] = {
    val data = fields.map { case (m, n) =>
      val matchValue = n.asInstanceOf[Int]
      if (matchValue == 1) {
        val res1 = extractField(js, m)
        (m, res1)
      }
      else {
        val res2 = extractField(js, m)
        if (res2 != "no") (m, res2.toDouble)
        else (m, "no")
      }
    }
    data.filter(_._2 != "no").toMap
  }
*/
  def timeStamp2TimeString(timestamp: String): String = {
    val outputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    val timeStr = outputFormat.format(timestamp)
    timeStr

  }


  //从一批数据中采用多数投票法返回属性值和对应的double值,例如Map("mv_type"->3.0)
  def majorityVote(feature_set: Seq[String]): Map[String, Double] = {
    val data = feature_set.map(Json.parse).map(jsonToArray)
    val featureLength = data.head.length
    val fields = fieldNames.filter(!_.endsWith("conf"))
    val dd = scala.collection.mutable.ArrayBuffer[Double]()
    for (i <- 0 until featureLength) {
      val newArray = scala.collection.mutable.ArrayBuffer[Double]()
      data.foreach { arrayFeature =>
        newArray += arrayFeature.apply(i)
      }
      dd += voteMaxElement(newArray.toArray)
    }
    assert(dd.length == fields.length, "array size not match")
    fields.zip(dd).toMap //Map("mv_type"->1.0......) //for debug,we not use filter
  }



  //
  def jsonToArray(js2: JsValue): Array[Double] = {
    val data = fieldNames.filter(!_.endsWith("conf")).map { t =>
      val res = extractField(js2, t)
      if (res != "no") res.toDouble else 3.14
    }
    data //
  }


  ///
  def voteMaxElement(data: Array[Double]): Double = {
    val uniqueSet = scala.collection.mutable.Set[Double]()
    data.foreach { t =>
      uniqueSet += t
    }
    val re = uniqueSet.map { e =>
      val len = data.count(_ == e)
      (e, len)
    }.toSeq.maxBy(_._2)._1
    re
  }


  def jsonToArrayConf(js3: JsValue): Array[Double] = {
    val data = fieldNames.filter(_.endsWith("conf")).map { t =>
      val res = extractField(js3, t)
      if (res != "no") res.toDouble else 3.14
    }
    data
  }

  def computeTime(time1: Double, time2: Double, deltaTime: Double): Boolean = {
    if (abs(time1 - time2) < deltaTime) true else false
  }

  def computeConf(featureSet: Seq[String]): Map[String, Double] = {
    val dataConf = featureSet.map(Json.parse).map(jsonToArrayConf)

    val length = dataConf.size
    val selectConf = dataConf.toList.apply(length / 2)

    val fields = fieldNames.filter(_.endsWith("conf"))
    fields.zip(selectConf).toMap //for debug, we not use filter
  }

  def computeOtherFeature(featureSet: Seq[String]): Map[String, Any] = {
    val data = featureSet.map(Json.parse).map { t =>
      val timestamp = if (extractField(t, "frame_timestamp") != "no") extractField(t, "frame_timestamp").toDouble else 3.14
      //val feature = extractField(t, "feature")
      val url = extractField(t, "url")
      val screenshot_path = extractField(t, "screenshot_path")
      val camera_id = extractField(t, "camera_id")
      val track_id = extractField(t, "track_id")
      val track_xmax = if (extractField(t, "track_xmax") != "no") extractField(t, "track_xmax").toDouble else 3.14
      val track_xmin = if (extractField(t, "track_xmin") != "no") extractField(t, "track_xmin").toDouble else 3.14
      val track_ymax = if (extractField(t, "track_ymax") != "no") extractField(t, "track_ymax").toDouble else 3.14
      val track_ymin = if (extractField(t, "track_ymin") != "no") extractField(t, "track_ymin").toDouble else 3.14
      val objnum = if (extractField(t, "objnum") != "no") extractField(t, "objnum").toDouble else 3.14
      val track_type = extractField(t, "track_type").toInt
      val mv_classification = extractField(t, "mv_classification")
      val company=extractField(t,"company")
      val aggs=extractField(t,"aggs")
      val gisLat=if (extractField(t ,"camera_gis") != "no") {
        val dd = t \ "camera_gis" \ "lat" match {
          case JsDefined(element)=>element
          case JsUndefined() => "noSuchElement"
        }
        if (dd.toString.replace("\"","") != "nocamerafound"){
          dd.toString.replace("\"","").toDouble
        }
        else 3.14
      } else 3.14

      val gisLon=if (extractField(t ,"camera_gis") != "no") {
        val dd = t \ "camera_gis" \ "lon" match {
          case JsDefined(element)=>element
          case JsUndefined() => "noSuchElement"
        }
        if (dd.toString.replace("\"","") != "nocamerafound"){
          dd.toString.replace("\"","").toDouble
        }
        else 3.14
      } else 3.14

      (timestamp, url, screenshot_path, camera_id, track_id, track_xmax, track_xmin, track_ymax, track_ymin, objnum,
        track_type, mv_classification,company,aggs,gisLat,gisLon)
    }
    val data2 = data.toList.apply(data.toList.size / 2)
    val res = Map("frame_timestamp" -> data2._1, "url" -> data2._2, "screenshot_path" -> data2._3
      , "camera_id" -> data2._4, "track_id" -> data2._5, "track_xmax" -> data2._6, "track_xmin" -> data2._7, "track_ymax" -> data2._8,
      "track_ymin" -> data2._9, "objnum" -> data2._10, "track_type" -> data2._11, "mv_classification" -> data2._12,
      "company"->data2._13,"aggs"->data2._14,"camera_gis"->Map("lat"->data2._15,"lon"->data2._16))
    res.toSeq.filter(_._2 != "no").filter(_._2 != 3.14).toMap
  }

  def parseFloat(ba: Array[Byte], startByte: Int): Float = {
    var i: Int = 0
    for (a <- startByte until startByte + FLOAT_SIZE)
      i = i | ((ba(a).toInt & BYTE_MASK) << (a * BYTE_SIZE))
    int2float(i)
  }

  def parseFloatArray(obj: Any): Array[Double] = {
    obj match {
      case text: String => parseFloatArray(decoder.decode(text))
      case arr: Array[Byte] => {
        val rest = arr.length % FLOAT_SIZE
        //if (rest != 0) throw new IllegalArgumentException("ByteArray not multiple of " + bytes + " long.")
        val retSize = arr.length / FLOAT_SIZE
        val ret = new Array[Double](retSize + {
          if (rest != 0) 1 else 0
        })
        for (f <- 0 until retSize)
          ret(f) = parseFloat(arr, f * FLOAT_SIZE)

        if (rest != 0) {
          ret(retSize) = 0
          for (b <- 0 until rest)
            ret(retSize) += arr(retSize * FLOAT_SIZE + b) << b * BYTE_SIZE
        }
        ret
      }
      case _ => throw new IllegalArgumentException("Unparsable type.")
    }
  }

  def codeFloat(f: Float, ba: Array[Byte], startByte: Int) = {
    val i = float2int(f)
    for (a <- startByte until startByte + FLOAT_SIZE)
    ba(a) = (i >> (a * BYTE_SIZE)).toByte
  }

  def codeFloatArray(arr: Array[Float]): String = {
    val ba = new Array[Byte](arr.length * FLOAT_SIZE)
    for (f <- arr.indices)
      codeFloat(arr(f), ba, f * FLOAT_SIZE)
    decoder.encodeToString(ba)
  }

  def get_avg_feature(array: Array[Array[Double]]): Array[Double] = {
    var states = collection.mutable.ArrayBuffer[Double]()
    var i = 0
    for (x <- array.head) {
      var m = 0.0
      for (s <- array) {
        m += s(i)
      }
      m /= array.length
      states += m
      i += 1
    }
    states.toArray
  }

  def computeFeature(featureSet: Seq[String]): Map[String, String] = {


   val res: String = extractField(Json.parse(featureSet.apply(featureSet.size / 2)),"feature")
     Map("feature" -> res)

  }

  def computeEarlyTime(myRdd: RDD[(String, Iterable[JsValue])]): Double = {
    val dd = myRdd.map { case (_, collectionValue) =>
      val data = collectionValue.map(e => (e \ "frame_timestamp").as[Double])
      val timeFirst = data.min
      timeFirst
    }
    dd.collect.min
  }

  def computeLastTime(myRdd: RDD[(String, Iterable[JsValue])]): Double = {
    val dd = myRdd.map { case (_, collectionValue) =>
      val data = collectionValue.toSeq.map(e => (e \ "frame_timestamp").as[Double])
      val timeFirst = data.max
      timeFirst
    }
    dd.collect.max
  }

  def rdd2rddByTime(myRdd: RDD[(String, Iterable[JsValue])], timeFilter: Double): RDD[(String, Iterable[(Double, JsValue)])] = {
    val data = myRdd.map { case (strId, collectValue) =>
      val timeAndJson = collectValue.map { e =>
        val time = (e \ "frame_timestamp").as[Double]
        (time, e)
      }
      val value1 = timeAndJson.filter(_._1 == timeFilter)
      (strId, value1)
    }
    data
  }

  def timeSeq(startTime: Double, endTime: Double, timeDelta: Double): Seq[Double] = {
    var time = startTime
    val timeSeqs = ArrayBuffer[Double]()
    while (time < endTime) {
      time += timeDelta
      timeSeqs += time
    }
    timeSeqs
  }



  def computeEachIdAndType(myRdd: RDD[(String, Iterable[JsValue])]): Seq[(String, String)] = {
    //myRdd.map(_._1).collect().toSeq
    myRdd.map { case (str_id, coll) =>
      val track_type = (coll.head \ "track_type").as[Int].toString
//      val company = extractField(coll.head,"company")
      (str_id,track_type)
    }.collect().toSeq
  }

  /**
    * put data into redis database
    */

  def data2redis(myRdd: RDD[(String, Iterable[JsValue])]): Unit = {

    myRdd.foreachPartition { partitionOfRecords =>
      val jedisInner = JedisClient.getJedisCluster()
      partitionOfRecords.foreach { case (m: String, n: Iterable[JsValue]) =>
        val str_id = m
        val value = n.map { jsonValue =>
          val timestamp = (jsonValue \ "frame_timestamp").as[Double]
          (jsonValue.toString(), timestamp)
        } // value里面的每一个值都是Seq(jsonvalue,timestamp)
      val sortedValue = value.toSeq.sortBy(_._2)
        val vv = mapAsJavaMap(sortedValue.toMap)
          .asInstanceOf[java.util.Map[java.lang.String, java.lang.Double]]
        jedisInner.zadd(str_id, vv)
        println("successfully to redis database!")
      }
      jedisInner.close()
    }
  }

  /**
    *  extract the position info from json
    */

  def computePosition(key: String, jedis: JedisCluster = JedisClient.getJedisCluster()): Map[String, Double] = {
    val start_xmin = if (extractField(Json.parse(jedis.zrange(key, 0, -1).head), "track_xmin") != "no")
      extractField(Json.parse(jedis.zrange(key, 0, -1).head), "track_xmin").toDouble
    else 0.0
    val start_xmax = if (extractField(Json.parse(jedis.zrange(key, 0, -1).head), "track_xmax") != "no")
      extractField(Json.parse(jedis.zrange(key, 0, -1).head), "track_xmax").toDouble
    else 0.0
    val start_ymin = if (extractField(Json.parse(jedis.zrange(key, 0, -1).head), "track_ymin") != "no")
      extractField(Json.parse(jedis.zrange(key, 0, -1).head), "track_ymin").toDouble
    else 0.0
    val start_ymax = if (extractField(Json.parse(jedis.zrange(key, 0, -1).head), "track_ymax") != "no")
      extractField(Json.parse(jedis.zrange(key, 0, -1).head), "track_ymax").toDouble
    else 0.0


    val end_xmin = if (extractField(Json.parse(jedis.zrange(key, 0, -1).last), "track_xmin") != "no")
      extractField(Json.parse(jedis.zrange(key, 0, -1).last), "track_xmin").toDouble
    else 0.0
    val end_xmax = if (extractField(Json.parse(jedis.zrange(key, 0, -1).last), "track_xmax")!="no")
      extractField(Json.parse(jedis.zrange(key, 0 ,-1).last),"track_xmax").toDouble
    else 0.0

    val end_ymin = if (extractField(Json.parse(jedis.zrange(key, 0, -1).last), "track_ymin") != "no")
      extractField(Json.parse(jedis.zrange(key, 0, -1).last), "track_ymin").toDouble
    else 0.0
    val end_ymax = if (extractField(Json.parse(jedis.zrange(key, 0, -1).last), "track_ymax") != "no")
      extractField(Json.parse(jedis.zrange(key, 0, -1).last), "track_ymax").toDouble
    else 0.0

    Map("startX" -> (start_xmin + start_xmax) / 2.0, "startY" -> (start_ymin + start_ymax) / 2.0,
      "endX" -> (end_xmax + end_xmin) / 2.0, "endY" -> (end_ymax + end_ymin) / 2.0)
  }



  def clusterKeys(jedis: JedisCluster = JedisClient.getJedisCluster(), key: String): scala.collection.mutable.Set[String] = {
    val clusterNodes = jedis.getClusterNodes.values()
    val keySet = scala.collection.mutable.Set[String]()
    clusterNodes.foreach { cn =>
      val resource: Jedis = cn.getResource
      keySet ++= resource.keys(key)

    }
    keySet
  }

def getKeysCollection(keyCollection:Seq[String],jedis:JedisCluster=JedisClient.getJedisCluster(),beforeTime:Double):Seq[String]= {
  keyCollection.flatMap {key:String=>

    val historyLastTime = jedis.zrangeWithScores(key, 0, -1).last.getScore
    if(!computeTime(beforeTime,historyLastTime,120)){
      Some(key)
    }
    else None
  }

}
  /**
    * wrapper:making use of implicit methods to acheive json to map
    * */


  implicit def jsvalue2Map(input:JsValue):Map[String,Any]={
    scala.util.parsing.json.JSON.parseFull(input.toString()).get.asInstanceOf[Map[String,Any]]
  }

  def getLastFeature(data:Seq[String]):Map[String,Any]={
        Json.parse(data.last).toMap
  }

  def checkFieldAtcameraGis(input:Map[String,Any]): Map[String,Any] ={
    val str1=input.get("camera_gis") match {
      case Some(gis) =>gis.toString
      case None => ""
    }
    val res=if (str1.contains("nocamerafound")) input ++ Map("camera_gis"->Map("lat"-> -1.0,"lon"-> -1.0)) else input
    res

  }

  def iterator2iter(jedis: JedisCluster = JedisClient.getJedisCluster()
                   ,originTuple:(String,String)):Map[String,Any]={
    val temp1=jedis.zrange(originTuple._1,0,-1).toSeq
    val start_time = jedis.zrangeWithScores(originTuple._1,0,-1).headOption match {
      case Some(head)=>head.getScore
      case None=>0.toDouble
    }
    val company=originTuple._1.split("_").last
    val screenShotPath = extractField(Json.parse(temp1.apply(temp1.size / 2)),"screenshot_path")
    checkFieldAtcameraGis(getLastFeature(temp1) ++ Map("doc_id"->originTuple._1) ++ Map("start_time"-> start_time) ++ Map("screenshot_path"->screenShotPath))
  }

//  def getMiddleTime = (time:Double,batchTimeMargin:Int)=>time + batchTimeMargin / 2
//
//  case class position_point(a:Double,b:Double)
//
//  def getNewRdd(time:Double,rdd1:RDD[(String,JsValue)]):RDD[(String,Seq[position_point])]={
//    rdd1.filter{case (_,b)=>
//      val type1=(b \ "track_type").as[Int].toString
//      type1=="1"
//    }.filter{ case (_,r2)=>
//         val time1=(r2 \ "frame_timestamp").as[Double]
//         time1==time
//       }.map{case (_,js1)=>
//    val camera_id=(js1 \ "camera_id").as[String]
//      (camera_id,js1)
//
//    }.groupByKey(50).map{case (camera_id,js2List)=>
//        val personsData=js2List.map{r=>
//          val start_xmin = if (extractField(r, "track_xmin") != "no")
//            extractField(r, "track_xmin").toDouble else 0.0
//          val start_xmax = if (extractField( r , "track_xmax") != "no")
//            extractField(r, "track_xmax").toDouble else 0.0
//          val x_average=(start_xmin + start_xmax) / 2.0
//          //   y position
//          val start_ymin = if (extractField(r,"track_ymin") != "no")
//            extractField(r, "track_ymin").toDouble else 0.0
//          val start_ymax = if (extractField( r , "track_ymax") != "no")
//            extractField(r, "track_ymax").toDouble else 0.0
//          val y_average=(start_ymin + start_ymax) / 2.0
//          position_point(x_average,y_average)
//        }
//      (camera_id,personsData.toSeq)
//    }
//
//  }
//
//  def getMatrix(rdd1:RDD[Seq[(position_point,JsValue]]):RDD[DenseMatrix[Double]]={
////    val len=rdd1.collect().size
////    val data=rdd1.collect().flatten
////    new DenseMatrix(len,2,data)
//    val rdd2=rdd1.map{a=>
//      val data=a.map{case (position_point(num1,num2),js1)=>
//       Array(num1,num2)
//      }.toArray.flatten
//      val len=a.size
//      new DenseMatrix(len,2,data)
//    }
//    rdd2
//  }
//
////  val euclideanDistance = (a: Vector[Double], b: Vector[Double]) => {
////    norm(a-b, 2)
////  }
////  val gdbscan = new GDBSCAN(
////    DBSCAN.getNeighbours(epsilon = 1, distance = euclideanDistance),
////    DBSCAN.isCorePoint(minPoints = 2)
////  )
//
//  def isFollowing(matrix:DenseMatrix[Double],min_points:Int,epsilon1:Double,personNum:Int):Boolean={
//    val euclideanDistance = (a: Vector[Double], b: Vector[Double]) => {
//      norm(a-b, 2)
//    }
//
//    val gdbscan = new GDBSCAN(
//      DBSCAN.getNeighbours(epsilon = epsilon1, distance = euclideanDistance),
//      DBSCAN.isCorePoint(minPoints = min_points)
//    )
//
//    val cluster = gdbscan cluster matrix
//    val clusterPoints = cluster.map(_.points.map(_.value.toArray))
//
//    clusterPoints.map(_.size).max >= personNum
//
//  }





}
