package xjcs



import java.text.SimpleDateFormat
import java.util
import java.util.concurrent.TimeUnit
import java.util.{Calendar, Date}

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.elasticsearch.spark.rdd.EsSpark
import play.api.libs.json.{JsValue, Json}
import redis.clients.jedis.JedisCluster

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.math.{abs, _}
/**
 * Created by zhangxinyi on 2017/10/27.
 */
object SuspeciousDetect{


  val BYTE_SIZE = 8
  val BYTE_MASK = 0xFF
  val FLOAT_SIZE = 4
  val DOUBLE_SIZE = 8

  val decoder = new org.apache.commons.codec.binary.Base64(-1, Array(), false)

  def int2float = java.lang.Float.intBitsToFloat _

  def float2int = java.lang.Float.floatToIntBits _

  def long2double = java.lang.Double.longBitsToDouble _

  def double2long = java.lang.Double.doubleToLongBits _


  def wandering_highspeed_night_detect(k: String, history: List[String], single_wandering_duration: Int, single_speed_lower: Float, single_speed_higher:Float, single_wandering_entropy: Float, single_night_limit: Int): Option[Map[String, Any]] = {
    val time_format = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    //    var record_speed = Map.empty[String, Any]
    var record_wandering = Map.empty[String, Any]
    var record_night = Map.empty[String, Any]
    // 夜间逗留检测

    val lastJson = Json.parse(history.last)
    val medianJson = Json.parse(history(history.length/2))
    val last_time = time_format.parse((lastJson \ "datetime").as[String])
    val camera_id = (lastJson \ "camera_id").as[String]
    val last_timestamp = (lastJson \ "frame_timestamp").as[Double]
    val first_timestamp = (Json.parse(history.head) \"frame_timestamp").as[Double]
    val screenshot_path = (medianJson \ "screenshot_path").as[String]
    val my_lat = (lastJson \ "camera_gis" \ "lat").as[Double]
    val my_lon = (lastJson \ "camera_gis" \ "lon").as[Double]
    val url =  (medianJson \ "url").as[String]
    val startTime = timestamp2data(first_timestamp.toLong)
    val endTime = timestamp2data(last_timestamp.toLong)
    //    println("history.length",history.length)
    if (last_time.getHours<single_night_limit & history.length>1200){

      record_night = Map("id" -> k, "end_time"->endTime,"start_time" ->startTime, "type" -> "night","camera_id"->camera_id,"screenshot_path"->screenshot_path,"url"->url,"camera_gis"->Map("lat"->my_lat,"lon"->my_lon),"screenshot_path"->screenshot_path,"url"->url)
      println("night detect bingo")
    }
    if (history.length >5) {
      val path_history = history.map{
        e => {
          val value = Json.parse(e)
          val x: Double = ((value \ "track_xmin").as[Int] + (value \ "track_xmax").as[Int]) / 2
          val y: Double = ((value \ "track_ymin").as[Int] + (value \ "track_ymax").as[Int]) / 2
          val w = abs((value \ "track_xmin").as[Int] - (value \ "track_xmax").as[Int])
          val time = (value \ "datetime").as[String]
          //          val timestamp = (value \ "frame_timestamp").as[Int]
          (x, y, w, time)
        }
      }

      val range = 1 to path_history.size-1
      val list_diff = range.map{
        r=>{
          val diff_x = path_history(r - 1)._1 - path_history(r)._1 + 0.000000001
          val diff_y = path_history(r - 1)._2 - path_history(r)._2
          val distance = sqrt((path_history(r - 1)._1 - path_history(r)._1) * (path_history(r - 1)._1 - path_history(r)._1) + (path_history(r - 1)._2 - path_history(r)._2) * (path_history(r - 1)._2 - path_history(r)._2)) / ((path_history(r - 1)._3 + path_history(r)._3) / 2)
          (diff_x,diff_y,distance)
        }
      }
      // 速度检测
      val now = Calendar.getInstance()
      val currentHour = now.get(Calendar.HOUR_OF_DAY)
      val distance = list_diff.takeRight(8).map(_._3).sum
      val jedisInner = JedisClient.getJedisCluster()
      //            val avgSpeedRedis = jedisInner.get("avgspeed")
//      val avgSpeedRedis = jedisInner.hget(camera_id,currentHour.toString)
      //      println("avgSpeedRedis",avgSpeedRedis,avgSpeedRedis.getClass)
      var avgSpeed:Float= single_speed_lower
//      if (!avgSpeedRedis.isEmpty){
//        if (avgSpeedRedis.toFloat>single_speed_lower*0.5)
//        {
//          avgSpeed = avgSpeedRedis.toFloat*2.5.toFloat
//        }
//      }
      val duration = getDateDiff(time_format.parse(path_history.takeRight(9).head._4), time_format.parse(path_history.last._4), TimeUnit.SECONDS).toInt
      val speed = distance / duration
      //      println("speed",speed)
      if (speed > avgSpeed) {

        //      if (speed > avgSpeed & speed < single_speed_higher) {
        val camera_id = k.split("_")(0)
        val track_id = k.split("_")(1)
        val jsonstr = Json.toJson(HighSpeedRecord(last_time,track_id,speed,camera_id,Map("lat"->my_lat,"lon"->my_lon),url ,screenshot_path)).toString()
        val record_speed =mapAsJavaMap(Map(jsonstr->last_timestamp)).asInstanceOf[java.util.Map[java.lang.String, java.lang.Double]]
        jedisInner.zadd("highspeed_"+camera_id,record_speed)
        //         data2redis_speed(camera_id,track_id,path_history.last._5.toDouble,(path_history.last._5-10).toDouble)
        //        record_speed = Map("id" -> k, "check_time" -> last_time, "type" -> 1,"Relative velocity"->speed)
        println("someone running")
      }

      //
      //      println("historylength",history.length)

      val avgStayRedis = jedisInner.hget(camera_id,currentHour.toString)
      var avgStay:Float=single_wandering_duration

//      if (!avgStayRedis.isEmpty){
//        if (avgStayRedis.toFloat>single_wandering_duration*0.5){
//          avgStay = (avgStayRedis.toFloat*5).toFloat
//        }
//      }
      jedisInner.close()

      if (history.length > avgStay) {
        val list_direction = get_direction(list_diff.toList.map{
          t=>{
            (t._1,t._2)
          }
        })
        val entropy = get_entropy(list_direction)
        //        println("entropy",entropy)
        if (entropy > single_wandering_entropy) {
          record_wandering = Map("id" -> k, "start_time" -> startTime, "end_time" -> endTime, "type" -> "staying","camera_id"->camera_id,"screenshot_path"->screenshot_path,"url"->url,"camera_gis"->Map("lat"->my_lat,"lon"->my_lon))
          println("someone staying")
        }
      }
      if (record_wandering.nonEmpty || record_night.nonEmpty) {
        Some(record_wandering ++ record_night)
      }
      else {
        None
      }
    }
    else {
      None
    }
  }

  case class HighSpeedRecord(check_time:Date,track_id:String,Relative_velocity:Double,camera_id:String,camera_gis:Map[String,Double],url:String,screenshot_path:String)
  implicit val fmt = Json.format[HighSpeedRecord]

  def getDateDiff(date1: Date, date2: Date, timeUnit: TimeUnit): Long = {
    val diffInMillies = date2.getTime() - date1.getTime()
    timeUnit.convert(diffInMillies, TimeUnit.MILLISECONDS)
  }

  def get_direction(list_diff: List[(Double,Double)]): List[Int] = {
    val degree = get_degree(list_diff)
    val list_direction = degree.map{
      x=>{
        if (x >= 0 & x < Pi / 4) 0
        else if (x >= Pi / 4 & x < Pi / 2) 1
        else if (x >= Pi / 2 & x < 3 * Pi / 4) 2
        else if (x >= 3 * Pi / 4 & x < Pi) 3
        else if (x >= Pi & x < 5 * Pi / 4) 4
        else if (x >= 5 * Pi / 4 & x < 3 * Pi / 2) 5
        else if (x >= 3 * Pi / 2 & x < 7 * Pi / 4) 6
        else 7
      }
    }
    list_direction
  }


  def get_degree(list_diff: List[(Double,Double)]): List[Double] = {
    //    val list_degree = scala.collection.mutable.MutableList[Double]()

    val list_degree = list_diff.map { case (x_i, y_i) => {
      if (y_i > 0 & x_i > 0) atan(y_i / x_i)
      else if (x_i < 0) atan(y_i / x_i) + Pi
      else atan(y_i / x_i) + 2 * Pi
    }
    }
    list_degree
  }

  def get_entropy(values: List[Int]): Double = {
    val uniqueVals = collection.SortedSet(values: _*)
    val lengthDouble: Double = values.length.toDouble
    var totalEntropy: Double = 0.0

    uniqueVals.foreach {
      value => {
        val occurrence: Double = values.count(_ == value) / lengthDouble
        totalEntropy += (log2(occurrence) * occurrence) * (-1.0)
      }
    }

    totalEntropy
  }

  def log2(x: Double): Double = scala.math.log(x)

  def get_same_pedestrian_from_es(key: String, mylast_data: JsValue,my_feature:Array[Double],scc: SparkContext, EM_dis: Double = 0.33, Cosine_sim: Double = 0.9): (Option[RDD[(String, ((Float, Float), String))]], Double, Double, String, String) = {
    val my_lat = (mylast_data \ "camera_gis" \ "lat").as[Double]
    val my_lon = (mylast_data \ "camera_gis" \ "lon").as[Double]
    //    val my_feature = parseFloatArray((mylast_data \ "feature").as[String])
    val camera_id = (mylast_data \ "camera_id").as[String]
    val my_company = (mylast_data \ "company").as[String]
    //    val my_exittime = (mylast_data \ "datetime").as[String]
    val my_frametimestamp = (mylast_data \ "frame_timestamp").as[Double].toLong
    //    val outputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    //    val my_exittime_date = outputFormat.parse(my_exittime)
    //    val pre_time = outputFormat.format(new GregorianCalendar(my_exittime_date.getYear + 1900, my_exittime_date.getMonth, my_exittime_date.getDate, my_exittime_date.getHours, my_exittime_date.getMinutes - 15, my_exittime_date.getSeconds).getTime)

    val pre_time = my_frametimestamp-900
    /**
     *
     */
    val queryarray1 = new Array[Any](8)
    queryarray1.update(0, "1")
    queryarray1.update(1, pre_time)
    queryarray1.update(2, my_frametimestamp)
    queryarray1.update(3, camera_id)
    queryarray1.update(4, "0.1km")
    queryarray1.update(5, my_lat.toString)
    queryarray1.update(6, my_lon.toString)
    queryarray1.update(7, my_company)
    val es_index = "attribute/history"
    val querystr1 = create_querystr1(queryarray1)
    //    println("querystr1",querystr1)
    val docs = EsSpark.esRDD(scc, es_index, querystr1).asInstanceOf[RDD[(java.lang.String, mutable.LinkedHashMap[java.lang.String, AnyRef])]]
    if (!docs.isEmpty()) {
      //      println("doc is not empty")
      val same_pedestrian_option = get_same_pedestrian(docs, my_feature, my_lat, my_lon,my_frametimestamp.toString, my_company, EM_dis, Cosine_sim)
      if (same_pedestrian_option._1.nonEmpty) {
        same_pedestrian_option
      }
      else {
        (None, my_lat, my_lon, my_frametimestamp.toString, my_company)
      }

    }
    else {
      //      println("doc is empty")
      (None, my_lat, my_lon, my_frametimestamp.toString, my_company)
    }
  }

  def create_querystr1(queryarray: Array[Any]): String = {
    """{"query": {"bool": {"must": [{"term": {"track_type":"""" + queryarray(0) + """"}},{"term": {"commany":"""" + queryarray(7) + """"}},{"range": {"frame_timestamp": {"gte":""" + queryarray(1) + ""","lt": """ + queryarray(2) + """}}}],"must_not": [{"term": {"camera_id":"""" + queryarray(3) + """"}}],"filter": {"geo_distance": {"distance":"""" + queryarray(4) + """","camera_gis": {"lat": """ + queryarray(5) + ""","lon": """ + queryarray(6) + """}}}}},"_source": ["p_feature","datetime"]}"""
  }

  def get_same_pedestrian(records: RDD[(java.lang.String, mutable.LinkedHashMap[java.lang.String, AnyRef])], feature_single: Array[Double], lat: Double, lon: Double, timestamp: String, company: String, EM_dis: Double, Cosine_sim: Double): (Option[RDD[(String, ((Float, Float), String))]], Double, Double, String, String) = {
    // records: RDD[(String,mutable.LinkedHashMap[String,AnyRef])]     ->docs
    val outputFormat2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    /**
     *
     */

    val potentials= records.map {
      case (k_pre, v_pre) => {
        val k_aft: String = k_pre
        val v_aft: Map[String, AnyRef] = v_pre.toMap
        (k_aft->v_aft)
      }
    }.map {
      case (k1, v1) => {
        val other_key = "p_" + v1("camera_id").toString + "_" + v1("track_id").toString
        //        val other_time = outputFormat2.parse(v1("datetime").asInstanceOf[String])
        val other_timestamp = v1("frame_timestamp").asInstanceOf[Long].toString
        val other_gis = v1("camera_gis").asInstanceOf[collection.mutable.LinkedHashMap[String, AnyRef]]
        val other_lat = other_gis("lat").asInstanceOf[Double].toFloat
        val other_lon = other_gis("lon").asInstanceOf[Double].toFloat
        val other_feature = parseFloatArray(v1("feature").toString)
        (other_key, (other_feature, (other_lat, other_lon), other_timestamp))
      }
    }
    val same_pedestrians = potentials.filter {
      case (k3, v3) => {
        retrieve_close(v3._1, feature_single, company, EM_dis, Cosine_sim)
      }
    }.map {
      case (k4, v4) => {
        (k4, (v4._2, v4._3))
      }
    }
    if (!same_pedestrians.isEmpty()) {
      (Option(same_pedestrians), lat, lon, timestamp, company)
    }
    else {
      (None, lat, lon, timestamp, company)
    }
  }

  def retrieve_close(other_feature: Array[Double], my_feature: Array[Double], company: String, EM_dis: Double, Cosine_sim: Double): Boolean = {
    val other_features_head = other_feature(0)
    val my_head = my_feature(0)
    //    println("myhead",my_head)
    if (other_features_head / my_head > 0.8 & other_features_head / my_head < 1.2) {
      if (company == "ks") {
        val distance = get_edistance(other_feature, my_feature)
        //              println("distance--",distance,"ks")
        if (distance < EM_dis) {
          true
        }
        else {
          false
        }
      }
      else {
        val distance = cosineSimilarity(other_feature, my_feature)
        if (distance > Cosine_sim) {
          true
        }
        else {
          false
        }
      }

    }
    else {
      false
    }
  }

  def get_edistance(xs: Array[Double], ys: Array[Double]): Double = {
    sqrt((xs zip ys).map { case (x, y) => pow(y - x, 2) }.sum)
  }

  def cosineSimilarity(x: Array[Double], y: Array[Double]): Double = {
    require(x.size == y.size)
    dotProduct(x, y) / (magnitude(x) * magnitude(y))
  }

  def dotProduct(x: Array[Double], y: Array[Double]): Double = {
    (for ((a, b) <- x zip y) yield a * b) sum
  }

  def timestamp2data(timestamp:Long):String={
    val df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    df.format(timestamp*1000L)
  }


  def magnitude(x: Array[Double]): Double = {
    math.sqrt(x map (i => i * i) sum)
  }

  def get_my_path_from_es(k:String,v:((Float,Float),String),scc:SparkContext):Option[RDD[(String,Map[String,AnyRef])]]={
    //    val outputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    val queryarray2 = new Array[String](4)
    queryarray2.update(0, k) // camera_id+track_id+track_type
    queryarray2.update(1, v._1._1.toString) //lat
    queryarray2.update(2, v._1._2.toString) //lon
    queryarray2.update(3, v._2) //time
    val querystr2 = create_querystr2(queryarray2)
    println("querystr2", querystr2)
    // path_docs
    val path_docs = EsSpark.esRDD(scc, "path_multiple_cameras_tmp/logs", querystr2).asInstanceOf[RDD[(java.lang.String,mutable.LinkedHashMap[java.lang.String,AnyRef])]]
    if (!path_docs.isEmpty()){
      //      println("path_docs.size",path_docs.count())
      val path_docs_map = path_docs.map{
        case (k_pre,v_pre)=>{
          val k_aft:String = k_pre
          val v_aft: Map[String,AnyRef] = v_pre.toMap
          (k_aft->v_aft)
        }
      }
      Option(path_docs_map)
    }
    else{
      None
    }

  }

  def create_querystr2(queryarray:Array[String]):String={
    """{"query": {"terms": {""""+queryarray(0)+"""": [""""+queryarray(1)+"""",""""+queryarray(2)+"""",""""+queryarray(3)+""""]}}}"""
  }

  def wandering_detect(k:String,v:Map[String,AnyRef],lat:Double,lon:Double,starttime:String,company:String,initial_k:String,mutiple_wandering_duration:Int=900,avg_occspeed:Double=0.000000005):Option[(Option[Map[String,Any]],Map[String,Any])]={
    //    val outputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    val history_path = v.filter {
      // 去除field id
      case (k3, v3) => filter_gis((k3, v3))
    }.map{
      case (k4,v4)=>{
        val lat_lon_time = v4.asInstanceOf[collection.mutable.Buffer[String]].toArray
        val my_other_lat = lat_lon_time.head.toFloat
        val my_other_lon = lat_lon_time(1).toFloat
        val my_other_time = lat_lon_time(2).toLong
        ((my_other_lat, my_other_lon), my_other_time)
      }
    }.toList
    val total_path = ((lat.toFloat, lon.toFloat), starttime.toLong)::history_path.sortBy(_._2)
    val multiple_wandering = multiple_wandering_cal(total_path ,k,mutiple_wandering_duration,avg_occspeed)
    val data = v++Map(initial_k-> ArrayBuffer(lat.toString, lon.toString, starttime), "id" -> k)
    if (multiple_wandering.nonEmpty) {
      Option(multiple_wandering,data)
    }
    else{
      Option(None,data)
    }
  }

  def filter_gis(value: (String, AnyRef)): Boolean = {
    if (value._1 == "id") {
      false
    }
    else {
      true
    }
  }


  def multiple_wandering_cal(paths:List[((Float,Float),Long)],path_id:String,mutiple_wandering_duration:Int,avg_occspeed:Double):Option[Map[String,Any]]={
    val outputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    val time = paths.last._2
    val base_time = time-2700
    //    val base_time = outputFormat.parse(outputFormat.format(new Date(time.getYear,time.getMonth,time.getDate,time.getHours,time.getMinutes-45,time.getSeconds)))
    val last_paths = paths.filter{t=>{compare_time(base_time,t._2)}}
    //    val duration = getDateDiff(last_paths.head._2,last_paths.last._2,TimeUnit.SECONDS).toInt
    val duration = abs(last_paths.head._2-last_paths.last._2)
    if (duration > mutiple_wandering_duration){
      val maxlat = last_paths.maxBy(_._1._1)._1._1
      val minlat = last_paths.minBy(_._1._1)._1._1
      val maxlon = last_paths.maxBy(_._1._2)._1._2
      val minlon = last_paths.minBy(_._1._2)._1._2
      val area = (maxlat-minlat)* (maxlon-minlon)
      val avg_speed = area/duration
      val occurrence_map = last_paths.groupBy(l => l._1).mapValues(_.size)
      val avg_occurrence = occurrence_map.foldLeft(0)(_+_._2)/occurrence_map.keys.size
      if (avg_occurrence/avg_speed > avg_occspeed){
        //      if (avg_occurrence/avg_speed > 0){
        val starttime = outputFormat.format(last_paths.head._2*1000L)
        val endtime = outputFormat.format(last_paths.last._2*1000L)
        val record = Map("type"->3,"id"->path_id,"starttime"->starttime,"endtime"->endtime)
        Some(record)
      }
      else{
        None
      }
    }
    else{
      None
    }
  }

  def compare_time(time1:Long,time2:Long):Boolean={
    if (time1<time2){
      true
    }
    else{
      false
    }
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
  def parseFloat(ba: Array[Byte], startByte: Int): Float = {
    var i: Int = 0
    for (a <- startByte until startByte + FLOAT_SIZE)
      i = i | ((ba(a).toInt & BYTE_MASK) << (a * BYTE_SIZE))
    int2float(i)
  }


  def totalHighSpeed(idSet:Set[String],lastTime:Double,beforeTime:Double,scc:SparkContext,jedisOuter:JedisCluster):Unit={
    val df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    println("highspeed redis finding")
//    val jedisOuter = JedisClient.getJedisCluster()
    val jcp3 = JedisClusterPipeline.pipelined(jedisOuter)
    //        println("speedbeforetime",beforeTime.toLong,lastTime.toLong)
    idSet.foreach{
      uniqueId=>{
        jcp3.zrangeByScore("highspeed_"+uniqueId.dropRight(1),lastTime-20,lastTime)
      }
    }
    val highSpeedResult = jcp3.syncAndReturnAll().toList
    jcp3.close()
    val highSpeedEs = highSpeedResult.flatMap{
      r=>{
        val record=r.asInstanceOf[util.LinkedHashSet[String]]
        //            val trackIdRedis = (Json.parse(record)\"track_id").as[String]
        val trackSet = record.map{
          t=>{
            val jsont = Json.parse(t)
            val track_id = (jsont\"track_id").as[String]
            val camera_id = (jsont\"camera_id").as[String]
            val lat = (jsont \ "camera_gis" \ "lat").as[Double]
            val lon = (jsont \ "camera_gis" \ "lon").as[Double]
            val url = (jsont\"url").as[String]
            val screenshot_path = (jsont\"screenshot_path").as[String]
            val speed = (jsont\"Relative_velocity").as[Double]
            //                (track_id,camera_id,lat,lon,url,screenshot_path,speed)
            (camera_id,lat,lon,Map(track_id->Map("speed"->speed,"screenshot"->screenshot_path,"url"->url)))
            (camera_id,lat,lon,track_id,speed,screenshot_path,url)
          }
        }.toSet
        if (trackSet.size>5){
          //              val trackSet2 = trackSet.map(_._4).flatten.toMap
          //              println((trackSet2++Map("camera_id"->trackSet.head._1,"count"->trackSet.size,"camera_gis"->Map("lat"->trackSet.head._2,"lon"->trackSet.head._2),"start_time"->df.format(beforeTime.toLong*1000L),"end_time"-> df.format(lastTime.toLong*1000L),"track_type"->"running")).toSeq)
          Some(Map("camera_id"->trackSet.head._1,"url"->trackSet.head._7,"screenshot_path"->trackSet.head._6,"count"->trackSet.size,"camera_gis"->Map("lat"->trackSet.head._2,"lon"->trackSet.head._2),"start_time"->df.format(beforeTime.toLong*1000L),"end_time"-> df.format(lastTime.toLong*1000L),"track_type"->"running"))
        }
        else{
          None
        }
      }
    }
//    jedisOuter.close()
//    scc.makeRDD(highSpeedEs).saveToEs("abnormal/realtime")
    EsSpark.saveToEs(scc.makeRDD(highSpeedEs), "abnormal/realtime")
  }

}
