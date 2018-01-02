package xjcs

import org.apache.spark.SparkContext
import org.apache.spark.mllib.clustering.KMeans
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.storage.StorageLevel
import play.api.libs.json.Json
import redis.clients.jedis.JedisCluster
import org.elasticsearch.spark._
import scala.collection.JavaConversions._
import scala.math._

object Following {

  def computeDegree(x: Double, y: Double): Double = {
    var re = 0.0
    if (x > 0 & y > 0) re = atan(x / y)
    else if (x < 0) re = atan(x / y) + Pi
    else re = atan(y / x) + 2 * Pi
    re
  }


  def keyToFeature(keys: Seq[String], jedis: JedisCluster = JedisClient.getJedisCluster(), beforeTime: Double): Seq[((String, String), (Double, Double, Double, Double, Double, Double))] = {
    val key2feature = keys.filter { key =>
      val track_type = key.charAt(key.length() - 1).toString
      track_type == "1"
    }.flatMap { key =>
      val historyLastTime: Double = jedis.zrangeWithScores(key, 0, -1).last.getScore
      if (!computeTime(beforeTime, historyLastTime, 30)) Some(key)
      else None
    }.map { key: String =>
      val headElement = jedis.zrange(key, 0, -1).toList.map(Json.parse).head
      val startTime = (headElement \ "frame_timestamp").as[Double]
      val startX = ((headElement \ "track_xmax").as[Int] + (headElement \ "track_xmin").as[Int]) / 2.0
      val startY = ((headElement \ "track_ymax").as[Int] + (headElement \ "track_ymin").as[Int]) / 2.0
      val lastElement = jedis.zrange(key, 0, -1).toList.map(Json.parse).last
      val endTime = (lastElement \ "frame_timestamp").as[Double]
      val endX = ((lastElement \ "track_xmax").as[Int] + (lastElement \ "track_xmin").as[Int]) / 2.0
      val endY = ((lastElement \ "track_xmax").as[Int] + (lastElement \ "track_xmin").as[Int]) / 2.0
      val deltaTime = endTime - startTime
      val degreePerson = computeDegree(endX - startX, endY - startY)
      val feature = extractField(Json.parse(jedis.zrange(key, 0, -1).toList.apply(jedis.zrange(key, 0, -1).toList.size / 2)), "feature")
      ((key, feature), (startX, startY, endX, endY, deltaTime, degreePerson)) // feature engineering,6 dimensions
    }
    key2feature
  }

  def computeFollowing(dataFeature: Seq[((String, String), (Double, Double, Double, Double, Double, Double))],
                       sc: SparkContext,
                       numClusters: Int,
                       numIterations: Int=10,
                       personThreshold: Int = 5
                      ): Option[(Array[String],Double)] = {
    val kmeansRdd = sc.parallelize(dataFeature.map { case (m, n) =>
      val ve = Array(n._1, n._2, n._3, n._4, n._5, n._6)
      (Vectors.dense(ve), m._1)
    },2).persist(StorageLevel.MEMORY_ONLY) //rdd with index
    //val idRdd=dataFeatureRdd.map{case ((m,_),index)=>(index,m)}    // feature(json)  with index
    val numAll=dataFeature.map(_._1).map(_._1).map(_.split("_")(2)).count(_=="1")
    if(dataFeature.size >= numClusters && numAll >10) {
      val timeStart=System.currentTimeMillis()
      val model = KMeans.train(kmeansRdd.map(_._1), numClusters, numIterations)
      val modelBro = sc.broadcast(model)
      //
      //    val predictIndex = clusters.predict(kmeansRdd.map(_._1)).zipWithIndex().map{case (label,index)=>(index,label)}
      //    val dataWithIndex=kmeansRdd.map{case (rdd1,index)=>(index,rdd1)}.join(predictIndex).persist(StorageLevel.MEMORY_AND_DISK)
      //
      // tuple2-->(key,value)    tuple6--->(a,b,,c,d,e,f)
      val rddAndLabelAndKey = kmeansRdd.map { case (vec, key) =>
        val label = modelBro.value.predict(vec)
        (vec, label, key)
      }
      val recordArray = Array.fill(numClusters)(0.0)
      (0 until numClusters).foreach { i =>
        recordArray(i) = model.computeCost(rddAndLabelAndKey.filter { case (_, label, _) =>
          label == i
        }.map(_._1))
    }
      val minLabel = recordArray.zipWithIndex.minBy(_._1)._2 // _._1是cost _._2是label
      val minCost = recordArray.zipWithIndex.minBy(_._1)._1 // 1是cost
      println(s"the min cost is -----------", minCost)
      val res = rddAndLabelAndKey.filter(_._2 == minLabel).map(_._3).collect()
      val timeEnd=System.currentTimeMillis()
      println("folllwing algorithme running time is",(timeEnd-timeStart).toDouble / 1000)

      val numOverAll = res.length
      if (numOverAll > personThreshold) {
        Some((res, minCost))
      }
        else None

  }

    else None
  }

  def following2Es(data:Seq[String],jedis:JedisCluster,time:Double,scc:SparkContext):Unit={
    val dataFeature=keyToFeature(data,jedis,time)
//    if(computeFollowing(dataFeature,scc,3).nonEmpty) {
//      val res = computeFollowing(dataFeature, scc, 3).get
//      val startTime = jedis.zrangeWithScores(res._1.apply(res._1.length / 2), 0, -1).head.getScore
//      val endTime = jedis.zrangeWithScores(res._1.apply(res._1.length / 2), 0, -1).last.getScore
//      val res1=Map("start_time"->startTime,"end_time"->endTime)
//      val lens = jedis.zrange(res._1.apply(res._1.length / 2), 0, -1).toSeq.size
//      val screen_path=extractField(Json.parse(jedis.zrange(res._1.apply(res._1.length / 2), 0, -1).toSeq.apply(lens / 2)),"screenshot_path")
//        scc.makeRDD(Seq(Json.parse(jedis.zrange(res._1.apply(res._1.length / 2), 0, -1)
//          .toSeq.apply(lens / 2)).toMap ++ Map("minCost" -> res._2) ++ Map("screenshot_path"->screen_path)++res1 ++Map("abnormal_type"->"following")))
//          .coalesce(2).saveToEs("abnormal/history")
//    }
    val currentTime = System.currentTimeMillis()
    val fileName="file:///opt/following_" + currentTime.toString()
    scc.parallelize(dataFeature,1).saveAsTextFile(fileName)
  }



}


