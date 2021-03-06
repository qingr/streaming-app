package tools.redis

import java.text.SimpleDateFormat
import java.util.{Date, Properties}

import org.slf4j.LoggerFactory

import scala.xml.XML

/**
 * Created by tsingfu on 15/4/28.
 */
object LoadFileIntoSingleHash {

  val logger = LoggerFactory.getLogger("tools.redis.LoadFileIntoSingleHash")
  val sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

  def main(args: Array[String]): Unit = {

    if (args.length != 1) {
      println("WARN: args.length = " + args.length + "\n" + "You should specify a confXmlFile")
      System.exit(-1)
    }

    val confXmlFile = args(0)
    loadfile2singlehash(confXmlFile)
  }

  def loadfile2singlehash(confXmlFile: String): Unit ={

    val props = init_props_fromXml(confXmlFile)

    val servers = props.getProperty("redis.servers").trim
    val database = props.getProperty("redis.database", "0").trim.toInt
    val timeout = props.getProperty("redis.timeout").trim.toInt
    val password = props.getProperty("redis.password")

    val maxTotal = props.getProperty("jedisPool.maxTotal").trim.toInt
    val maxIdle = props.getProperty("jedisPool.maxIdle").trim.toInt
    val minIdle = props.getProperty("jedisPool.minIdle").trim.toInt

    val from = props.getProperty("load.from").trim
    assert(from=="file", "WARN: support only file From now")

    val filename = props.getProperty("load.filename").trim
    val fileEncode = props.getProperty("load.fileEncode").trim
    val columnSeperator = props.getProperty("load.columnSeperator").trim

    val hashName = props.getProperty("load.hashName").trim
    val fieldIdxes = props.getProperty("load.fieldIdxes").trim.split(",").map(_.trim.toInt)
    val fieldSeperator = props.getProperty("load.fieldSeperator").trim
    val valueIdxes = props.getProperty("load.valueIdxes").trim.split(",").map(_.trim.toInt)
    val valueSeperator = props.getProperty("load.valueSeperator").trim

    val batchLimit = props.getProperty("load.batchLimit").trim.toInt
//    val numThreads = props.getProperty("load.numThreads").trim.toInt
    val loadMethod = props.getProperty("load.method").trim


    val startMS = System.currentTimeMillis()
    var runningTime: Long = -1

    // 初始化 jedisPool, jedis, pipeline
    val jedisPools = servers.split(",").map(server=>{
      val hostPort = server.split(":").map(_.trim)
      val host = hostPort(0)
      val port = hostPort(1).toInt
      println("host = " + host +", port ="+port)
      RedisUtils.init_jedisPool(host, port, timeout, database, password, maxTotal, maxIdle, minIdle)
    })
    val numPools = jedisPools.length
    val jedises = jedisPools.map(_.getResource)
    val pipelines = jedises.map(_.pipelined)

    //获取文件记录数
    val fileRecordsNum = scala.io.Source.fromFile(filename, fileEncode).getLines().length

    var positionIdx = 0
    var numInBatch = 0
    var numBatches = 0
    var jedisPoolId = 0

    for (line <- scala.io.Source.fromFile(filename, fileEncode).getLines()) {
      val lineArray = line.split(columnSeperator).map(_.trim)

      val field = (for(idx <- fieldIdxes) yield lineArray(idx)).mkString(fieldSeperator)
      val value = (for(idx <- valueIdxes) yield lineArray(idx)).mkString(valueSeperator)

      jedisPoolId = numBatches % numPools

      pipelines(jedisPoolId).hset(hashName, field, value)
      positionIdx += 1
      numInBatch += 1

      if(numInBatch == batchLimit){
        try{
          pipelines(jedisPoolId).sync()
        }catch {
          case ex:Exception =>
            println(sdf.format(new Date()) + "\n" + ex.printStackTrace())

            //中途出现异常，并不退出
            println(sdf.format(new Date()) + " retry with new jedis after sleep 30 s ...")
            jedisPools(jedisPoolId).returnResourceObject(jedises(jedisPoolId))
            Thread.sleep(30 * 000)

            jedises(jedisPoolId) = jedisPools(jedisPoolId).getResource
            pipelines(jedisPoolId) = jedises(jedisPoolId).pipelined
        }

        runningTime = System.currentTimeMillis() - startMS
        println(sdf.format(new Date()) + " [info] loading file " + filename +
                ", status : positionIdx = " + positionIdx + ", progress percent = " + (positionIdx * 100.0 / fileRecordsNum) +" %" +
                ", runningTime = " + runningTime +", speed = " + positionIdx / (runningTime / 1000.0) +" records/s")

        numInBatch = 0
        numBatches += 1
      }
    }

    try{
      pipelines(jedisPoolId).sync()
    } catch { // 捕获最后一次执行异常，退出
      case ex:Exception =>
        println("=" * 30 + " " + sdf.format(new Date()) + "\n" + ex.printStackTrace())
//        jedisPools(jedisPoolId).returnResourceObject(jedises(jedisPoolId))
    }

    //打印统计信息
    val elapsedMS = System.currentTimeMillis() - startMS
    println(sdf.format(new Date()) + " [info] load file " + filename + " finished. " +
            "records = "+positionIdx+
            ", elspsed = " + elapsedMS + " ms, " + elapsedMS / 1000.0 + " s, " + elapsedMS / 1000.0 / 60.0 + " min" +
            ", speed = " + positionIdx / (elapsedMS / 1000.0) +" records/s" )

    //释放资源
    for(i<- 0 until numPools){
      jedisPools(i).returnResourceObject(jedises(i))
    }
    jedisPools.foreach(_.close())

  }

  def init_props_fromXml(confXmlFile: String): Properties ={

    val conf = XML.load(confXmlFile)
    val servers = (conf \ "redis" \ "servers").text.trim

    val database = (conf \ "redis" \ "database").text.trim
    val timeout = (conf \ "redis" \ "timeout").text.trim
    val passwd = (conf \ "redis" \ "password").text.trim
    val password = if (passwd == "" || passwd == null) null else passwd

    val maxTotal = (conf \ "jedisPool" \ "maxTotal").text.trim
    val maxIdle = (conf \ "jedisPool" \ "maxIdle").text.trim
    val minIdle = (conf \ "jedisPool" \ "minIdle").text.trim

    val from = (conf \ "load" \ "from").text.trim
    val filename = (conf \ "load" \ "filename").text.trim
    val fileEncode = (conf \ "load" \ "fileEncode").text.trim

    val columnSeperator = (conf \ "load" \ "columnSeperator").text.trim
    val hashName = (conf \ "load" \ "hashName").text.trim
    val fieldIdxes = (conf \ "load" \ "fieldIdxes").text.trim
    val fieldSeperator = (conf \ "load" \ "fieldSeperator").text.trim
    val valueIdxes = (conf \ "load" \ "valueIdxes").text.trim
    val valueSeperator = (conf \ "load" \ "valueSeperator").text.trim

    val batchLimit = (conf \ "load" \ "batchLimit").text.trim
//    val numThreads = (conf \ "load" \ "numThreads").text.trim
    val loadMethod = (conf \ "load" \ "method").text.trim

    val props = new Properties()
    props.put("redis.servers", servers)
    props.put("redis.database", database)
    props.put("redis.timeout", timeout)

    if(password != null || password == "") props.put("redis.password", password)

    props.put("jedisPool.maxTotal", maxTotal)
    props.put("jedisPool.maxIdle", maxIdle)
    props.put("jedisPool.minIdle", minIdle)

    props.put("load.from", from)
    props.put("load.filename", filename)
    props.put("load.fileEncode", fileEncode)
    props.put("load.columnSeperator", columnSeperator)

    props.put("load.hashName", hashName)
    props.put("load.fieldIdxes", fieldIdxes)
    props.put("load.fieldSeperator", fieldSeperator)
    props.put("load.valueIdxes", valueIdxes)
    props.put("load.valueSeperator", valueSeperator)

    props.put("load.batchLimit", batchLimit)
//    props.put("load.numThreads", numThreads)
    props.put("load.method", loadMethod)

    println("="*80)
    props.list(System.out)
    println("="*80)

    props
  }



}
