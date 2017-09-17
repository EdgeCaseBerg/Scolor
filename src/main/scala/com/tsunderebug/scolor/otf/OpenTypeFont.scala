package com.tsunderebug.scolor.otf

import java.nio.{ByteBuffer, ByteOrder}

import com.tsunderebug.scolor.otf.types.Offset32
import com.tsunderebug.scolor.table.Table
import com.tsunderebug.scolor._
import spire.math.{UByte, UInt, ULong, UShort}

import scala.collection.mutable

class OpenTypeFont extends Font {

  private val buff: mutable.Map[ULong, UByte] = mutable.Map()

  private val offsets: mutable.Map[Data, Offset] = mutable.Map()

  private val tableSeq: mutable.Seq[Table] = mutable.Seq()

  def tables: Seq[Table] = tableSeq

  override def nextAvailableOffset(data: Data): Offset = {
    if(offsets.contains(data)) {
      offsets(data)
    } else {
      val (d: Data, o: Offset) = offsets.toSeq.maxBy(_._2.position.toLong)
      val op = (o.position + d.length).toLong
      val offset = Offset32(op + (4 - (op % 4)))
      offsets += (data -> offset)
      offset
    }
  }

  override def queueBytes(o: Offset, b: Array[UByte]): Unit = {
    queueMapBytes(buff, o, b)
  }

  private def queueMapBytes(d: mutable.Map[ULong, UByte], o: Offset, b: Array[UByte]): Unit = {
    b.indices.foreach((i) => {
      d += (ULong(o.position.toLong) -> b(i))
    })
  }

  implicit def uShortToByteArray(u: UShort): Array[UByte] = {
    Array((u.toInt & 0xFF00) >> 8, u.toInt & 0xFF).map(UByte(_))
  }

  implicit def uIntToByteArray(u: UInt): Array[UByte] = {
    Array((u.toLong & 0xFF000000) >> 24, (u.toLong & 0xFF0000) >> 16, (u.toLong & 0xFF00) >> 8, u.toLong & 0xFF).map((l) => UByte(l.toInt))
  }

  implicit def stringToByteArray(s: String): Array[UByte] = {
    s.getBytes.map(UByte(_))
  }

  private def lpow(n: Int): (Int, Int) = {
    var res = 2
    var p = 1
    while (res < n) {
      res *= 2
      p += 1
    }
    (res / 2, p - 1)
  }

  private def openTypeChecksum(table: Table): UInt = {
    val intBuf = ByteBuffer.wrap(table.getBytes(this).map(_.signed)).order(ByteOrder.BIG_ENDIAN).asIntBuffer
    UInt(intBuf.array.sum)
  }

  private def offsetMapToData(m: mutable.Map[Offset, Data]): mutable.Map[ULong, UByte] = {
    m.map((t) => (ULong(t._1.position.toLong), t._2.getBytes(this))).flatMap { case (k, arr) => arr.zipWithIndex.map {case (v, idx) => (k + ULong(idx)) -> v }}
  }

  def clear(): Unit = {
    buff.clear()
    offsets.clear()
  }

  override def getBytes: Array[Byte] = {
    val headerLen = 12 + (16 * tables.length)
    val headerData: mutable.Map[ULong, UByte] = mutable.Map()
    queueMapBytes(headerData, Offset32(0), "OTTO")
    queueMapBytes(headerData, Offset32(4), UShort(tables.length))
    val (pow, exp) = lpow(tables.length)
    queueMapBytes(headerData, Offset32(6), UShort(pow * 16))
    queueMapBytes(headerData, Offset32(8), UShort(exp))
    queueMapBytes(headerData, Offset32(10), UShort((tables.length - pow) * 16))
    tables.zipWithIndex.foreach((t) => {
      queueMapBytes(headerData, Offset32((t._2 * 16) + 12), t._1.name)
      queueMapBytes(headerData, Offset32((t._2 * 16) + 16), openTypeChecksum(t._1))
      queueMapBytes(headerData, Offset32((t._2 * 16) + 20), nextAvailableOffset(t._1).getBytes(this))
      queueMapBytes(headerData, Offset32((t._2 * 16) + 24), t._1.length)
    })
    val totalData: mutable.Map[ULong, UByte] = headerData ++ offsetMapToData(offsets.map(_.swap).map((t) => (Offset32(t._1.position.toLong + headerLen), t._2)))
    val largestOffset = totalData.maxBy(_._1)._1.toInt
    val bb: ByteBuffer = ByteBuffer.allocate(largestOffset)
    totalData.foreach((t) => bb.put(t._1.toInt, t._2.signed))
    bb.array()
  }

}
