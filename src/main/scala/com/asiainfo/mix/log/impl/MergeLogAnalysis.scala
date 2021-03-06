package com.asiainfo.mix.log.impl

import com.asiainfo.mix.streaming_log.StreamAction
import org.apache.spark.SparkConf
import org.apache.spark.streaming.StreamingContext._
import org.apache.spark.streaming.dstream.DStream
import com.asiainfo.mix.streaming_log.LogTools
import scala.collection.mutable.ArrayBuffer
import org.apache.spark.Logging
import com.asiainfo.spark.stream.report.LogReportUtil
import java.util.Calendar

/**
 * @author surq
 * @since 2014.07.15
 * 汇总日志更新mysql 流处理
 */
class MergeLogAnalysis extends StreamAction with Serializable {

  /**
   * @param inputStream:log流数据<br>
   * @param xmlParm:解析logconf.xml的结果顺序依次是:<br>
   * 0、kafaArray---[configuration/dataSource/kafakaOut]<br>
   * 1、dbSourceArray----[configuration/dataSource/dbSource]<br>
   * 2、mixLogArray----[configuration/logProperties/log]<br>
   * 3、tablesArray----[configuration/tableDefines/table]<br>
   */
  override def run(inputStream: DStream[Array[(String, String)]], xmlParm: Seq[Array[(String, String)]]): DStream[String] = {
    printInfo(this.getClass(), "MergeLogAnalysis is running!")
    val kafaMap = xmlParm(0).toMap
    val dbSourceArray = xmlParm(1)
    val logPropertiesMap = xmlParm(2).toMap
    val tablesMap = xmlParm(3).toMap

    // 输出kafka配置 
    val kafkaseparator = kafaMap("separator")
    // rowkey 连接符
    val separator = "asiainfoMixSeparator"

    // 计算记录时间
    val logSpace = logPropertiesMap("logSpace")

    //输出为表结构样式　用
    val tbname = tablesMap("tableName")
    val thistopic = logPropertiesMap("topicLogType").trim
    val splitNum = "splitNum"

    // spark监控系统用
    // param（［kafka brokers］，［监控系统所占用的topic名称］，［所监控的topic］，［监控日志所用分隔符］）
    val reportEnv: Seq[String] = Seq(kafaMap("brokers"), "report", thistopic, "%")
    val classname = this.getClass().getName()
    
    inputStream.map(record => {
      val itemMap = record.toMap
      printDebug(this.getClass(), "source DStream:" + itemMap.mkString(","))
      val rowKey = itemMap("rowKey")
      (rowKey, record)
    }).groupByKey.map(f => {

      // 创建db表结构并初始化
      var dbrecord = Map[String, String]()
      // 去除前取数据处理时，拼接的rowKey字段和长度，此字段数据库中不存在
      val exceptItems = Array("rowKey", "log_length")
      // 汇总所有类型log日志更新的字段
      f._2.foreach(record => {
        val items = for (enum <- record if (enum._2 != "")) yield enum
        items.foreach(f => {
          if (!exceptItems.contains(f._1)) {
            dbrecord += ((f._1) -> (dbrecord.getOrElse(f._1, "0").toFloat + f._2.toFloat).toString)
          }
        })
      })

      // 填充db表结构中的主key部分
      val keyarray = (f._1).split(separator)
      dbrecord += (("activity_id") -> keyarray(0))
      dbrecord += (("order_id") -> keyarray(1))
      dbrecord += (("material_id") -> keyarray(2))
      dbrecord += (("ad_id") -> keyarray(3))
      dbrecord += (("size_id") -> keyarray(4))
      dbrecord += (("area_id") -> keyarray(5))
      dbrecord += (("media") -> keyarray(6))
      dbrecord += (("ad_pos_id") -> keyarray(7))
      val logdate = getlogtime(keyarray(8), logSpace)
      dbrecord += (("start_time") -> logdate._1)
      dbrecord += (("end_time") -> logdate._2)

      val selectKeyArray = ArrayBuffer[(String, String)]()
      selectKeyArray += (("activity_id") -> keyarray(0))
      selectKeyArray += (("order_id") -> keyarray(1))
      selectKeyArray += (("material_id") -> keyarray(2))
      selectKeyArray += (("ad_id") -> keyarray(3))
      selectKeyArray += (("size_id") -> keyarray(4))
      selectKeyArray += (("area_id") -> keyarray(5))
      selectKeyArray += (("media") -> keyarray(6))
      selectKeyArray += (("ad_pos_id") -> keyarray(7))
      selectKeyArray += (("start_time") -> logdate._1)
      selectKeyArray += (("end_time") -> logdate._2)
      printDebug(this.getClass(), "db updata before: " + dbrecord.mkString(","))
      val tuple3: Tuple3[String, Map[String, String], ArrayBuffer[(String, String)]] = (f._1, dbrecord, selectKeyArray)
      tuple3
    }).mapPartitions(LogReportUtil.monitor(_, reportEnv, Seq(classname, "3-groupByKey_map_after"))).mapPartitions(mp => {
      val starttime_sql = Calendar.getInstance().getTimeInMillis()
    
      val connection = LogTools.getConnection(dbSourceArray)
      var count = 0l
      val tmpList: ArrayBuffer[String] = ArrayBuffer[String]()
      for (patition <- mp) {
        tmpList += patition._1
        val dbrecord = patition._2
        val selectKeyArray = patition._3
        count = count + 1
        // 调用db查看有无数据存在
        //无则插入，有则更新
        LogTools.updataMysql(tbname, connection, selectKeyArray.toArray, dbrecord.toArray)
      }
      LogTools.closeConnection(connection)
      // TODO
      val perRecord_sqltime =(Calendar.getInstance().getTimeInMillis() - starttime_sql)/count
      LogReportUtil.kafkaSend("per record need time:"+perRecord_sqltime.toString, kafaMap("brokers"), "report")
      
      tmpList.iterator
    }).map(f => {f})
  }

  /**
   * 根据logtime的时间，取logtime所在的时间范围<br>
   */
  def getlogtime(logdate: String, logSpace: String): Tuple2[String, String] = {
    // 补全起止时间格式
    var start_time = ((logdate.substring(10)).toInt * logSpace.toInt).toString
    start_time = "00" + start_time
    start_time = start_time.substring(start_time.length - 2)
    start_time = logdate.substring(0, 10) + start_time + "00"
    var end_time = ((((logdate.substring(10)).toInt + 1) * logSpace.toInt) - 1).toString
    end_time = "00" + end_time
    end_time = end_time.substring(end_time.length - 2)
    end_time = logdate.substring(0, 10) + end_time + "59"
    (start_time, end_time)
  }
}