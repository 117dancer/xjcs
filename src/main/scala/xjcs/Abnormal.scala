package xjcs

import play.api.libs.json.Json
import redis.clients.jedis.JedisCluster
import scala.collection.JavaConversions._

object Abnormal {

  def findAbnormal(data: Seq[(String,String)],jedis:JedisCluster): Seq[Map[String,Any]] = {
      data.filter(_._2.toInt >= 4).map{case (id:String,track_type:String)=>
        val jedis=JedisClient.getJedisCluster()
        val tempData=jedis.zrange(id,0,-1).toSeq
        val company=id.split("_").last
        val abnormal_type = track_type match {
          case "4" => "flag"
          case "5" => "weapon"
          case "6" => "flame"
        }
        val screen_path=extractField(Json.parse(tempData.apply(tempData.size / 2)),"screenshot_path")
        if (company=="st"){
          getLastFeature(tempData) ++ Map("id"->id) ++ Map("abnormal_type"->abnormal_type) ++ Map("screenshot_path"->screen_path)
        }
        else  {
          val partCharacter1 = majorityVote(tempData)
          val partConf1 = computeConf(tempData)
          val partOther1 = computeOtherFeature(tempData)
            val feature1 = computeFeature(tempData)
            val res1: Map[String, Any] = partCharacter1 ++ partConf1 ++ partOther1 ++ feature1 ++ Map("id" -> id) ++ Map("abnormal_type"->abnormal_type)
            res1.toSeq.filter(_._2 != 3.14).toMap ++ Map("screenshot_path"->screen_path)
          }
  }

  }

def findAbnormalHistory(historyKeys:scala.collection.mutable.Set[String],jedisOuter:JedisCluster,beforeTime:Double): Seq[Map[String,Any]] ={
  historyKeys.toSeq.filter(_.split("_")(2).toInt >= 4).flatMap{ key:String =>        //某一个key
    val historyLastTime = jedisOuter.zrangeWithScores(key, 0, -1).last.getScore
    //val historyFirstTime = jedisOuter.zrangeWithScores(key,0,-1).head.getScore
    if (!computeTime(beforeTime, historyLastTime, 120)) {
      println("absolutely exists outCamera person!")
      val data = jedisOuter.zrange(key, 0, -1).toSeq
      //findAbnormal(data.asInstanceOf[Set[String]])
      val partCharacter = majorityVote(data)
      val partConf = computeConf(data)
      val partOther = computeOtherFeature(data)
      val historyFirstTime = jedisOuter.zrangeWithScores(key, 0, -1).head.getScore
      val position = computePosition(key, jedisOuter)
      val company = key.split("_").last
      val abnormal_type =key.split("_")(2) match {
        case "4"=>"flag"
        case "5"=>"weapon"
        case "6"=>"flame"
      }
      val screen_path = extractField(Json.parse(data.apply(data.size / 2)),"screenshot_path")
      val timeMapTuple = Map("startTime" -> historyFirstTime, "endTime" -> historyLastTime)
      if (company == "st") {
        Some(getLastFeature(data) ++ timeMapTuple ++ position ++ Map("id" -> key)++Map("abnormal_type"->abnormal_type)++Map("screenshot_path"->screen_path))
      }
      else {
          val feature = computeFeature(data) ++ Map("abnormal_type"->abnormal_type)
          val res3: Map[String, Any] = partCharacter ++ partConf ++ partOther ++ timeMapTuple ++ position ++ feature ++ Map("id" -> key)
          val res4 = res3.toSeq.filter(_._2 != 3.14).toMap ++ Map("screenshot_path"->screen_path)
          Some(res4)
      }

    }
    else None
  }
}




}
