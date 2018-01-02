package xjcs
import breeze.linalg._
//import breeze.numerics._
//import GDBSCAN._
import play.api.libs.json.JsValue
import org.apache.spark.rdd._

//import scala.collection.mutable.ArrayBuffer
object following2 {

  def getMiddleTime = (time:Double,batchTimeMargin:Int)=>time + batchTimeMargin / 2

  case class position_point(a:Double,b:Double)

  def getNewRdd(time:Double,rdd1:RDD[(String,JsValue)]):RDD[(String,Seq[position_point])]={
    rdd1.filter{case (_,b)=>
      val type1=extractField(b,"track_type").toString
      if(type1=="1") true else false
    }.filter{ case (_,r2)=>
      val time1=extractField(r2 ,"frame_timestamp").toDouble
      if(time1==time) true else false
    }.map{case (_,js1)=>
      val camera_id=extractField(js1,"camera_id").toString
      (camera_id,js1)

    }.groupByKey(50).map{case (camera_id,js2List)=>
      val personsData=js2List.map{r=>
        val start_xmin = if (extractField(r, "track_xmin") != "no")
          extractField(r, "track_xmin").toDouble else 0.0
        val start_xmax = if (extractField( r , "track_xmax") != "no")
          extractField(r, "track_xmax").toDouble else 0.0
        val x_average=(start_xmin + start_xmax) / 2.0
        //   y position
        val start_ymin = if (extractField(r,"track_ymin") != "no")
          extractField(r, "track_ymin").toDouble else 0.0
        val start_ymax = if (extractField( r , "track_ymax") != "no")
          extractField(r, "track_ymax").toDouble else 0.0
        val y_average=(start_ymin + start_ymax) / 2.0
        position_point(x_average,y_average)
      }
      (camera_id,personsData.toSeq)
    }

  }

  def getMatrix(r1:Seq[position_point]):DenseMatrix[Double]={
        val len=r1.size
    //    val data=rdd1.collect().flatten
    //    new DenseMatrix(len,2,data)
    val data=r1.map{case position_point(num1,num2)=>
        Array(num1,num2)}.toArray.flatten

      new DenseMatrix(len,2,data)
    }



  //  val euclideanDistance = (a: Vector[Double], b: Vector[Double]) => {
  //    norm(a-b, 2)
  //  }
  //  val gdbscan = new GDBSCAN(
  //    DBSCAN.getNeighbours(epsilon = 1, distance = euclideanDistance),
  //    DBSCAN.isCorePoint(minPoints = 2)
  //  )

  def isFollowing(matrix:DenseMatrix[Double],min_points:Int,epsilon1:Double,personNum:Int):(Boolean,position_point)={
    val euclideanDistance = (a: Vector[Double], b: Vector[Double]) => {
      norm(a-b, 2)
    }

    val gdbscan = new GDBSCAN(
      DBSCAN.getNeighbours(epsilon = epsilon1, distance = euclideanDistance),
      DBSCAN.isCorePoint(minPoints = min_points)
    )

    val cluster = gdbscan cluster matrix
    val clusterPoints = cluster.map(_.points.map(_.value.toArray))
    val arr=clusterPoints.maxBy(_.size).head
    (clusterPoints.map(_.size).max >= personNum,position_point(arr(0),arr(1)))

  }



}
