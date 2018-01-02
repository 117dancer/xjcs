
//import play.api.libs.json.{JsDefined, JsUndefined, JsValue, Json}
//val str1="""{"field1":12,"field2":{"sub_field1":1,"sub_fileds2":2}}"""
////Json.parse(str1) \ "field2"
//def extractField(data: JsValue, field: String): String = {
//  val dd = data \ field match {
//    case JsDefined(name) => name
//    case JsUndefined() => "no"
//  }
//  dd.toString.replace("\"", "")
//}
//
//val res1=Json.stringify(Json.obj(
//     "field1" -> Json.obj(
//      "field11" -> 22,
//      "field12" -> Json.arr("alpha", 123L))
//))
//
//val dd=Json.parse(res1) \ "field1" \ "field11" match{
//  case JsDefined(name) => name
//  case JsUndefined() => "mm"
//}
//
//dd.toString.replace("\"","").toDouble
//val a=Map("a"->12,"camera_gis"->Map("lat"->12,"lon"->16))
//a.getOrElse("camera_gis","").toString.contains("16")
//
//a ++ Map("camera_gis" ->Map("gg"->33))
//-1.toDouble
//
//
//while(true):
////  println("gg")
//val a=Map("a"->12)
//val dd=a.get("a")
//val b=Map{"doc_id"->a.getOrElse("a",0)}
//case class tt(name:String)
//
//implicit val dd=Json.format[tt]
//val a=Array(1,23,3)
//a.mkString(",",)
//val dd=Seq(1,2,4)
//dd.max
val a:Map[String,Any]=Map("aa"->12,"34"->"gg")
a.getOrElse("aa",0.0)
