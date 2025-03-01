package dedup
package bloomfilter

import spinal.core._
import spinal.lib._

case class DecodedReadyInstr(conf: DedupConfig) extends Bundle{
  val CRCHash = Vec(Bits(32 bits), conf.bfConf.k)
  val opCode = DedupCoreOp()
  val tag = UInt(conf.bfConf.instrTagWidth bits)
}

case class DecodedWaitingInstr(conf: DedupConfig) extends Bundle{
  val pageCount = UInt(conf.LBAWidth bits)
  val opCode = DedupCoreOp()
  val tag = UInt(conf.bfConf.instrTagWidth bits)
}

case class BloomFilterInstrDecoder(conf: DedupConfig) extends Component{

  val instrBitWidth = DedupCoreOp().getBitsWidth
  val bfConf = conf.bfConf

  val io = new Bundle {
    /* input raw Instr Stream: 512bits*/
    val rawInstrStream = slave Stream (Bits(conf.instrTotalWidth bits))

    /* output instr stream */
    val readyInstrStream = master Stream (DecodedReadyInstr(conf)) 
    val waitingInstrStream = master Stream (DecodedWaitingInstr(conf))
  }

  // initialization
  io.rawInstrStream.setBlocked()
  io.readyInstrStream.setIdle()
  io.waitingInstrStream.setIdle()

  val isNeededInstr = Bool() default(False)

  val tagGenerator = Counter(bfConf.instrTagWidth bits, inc = isNeededInstr & io.rawInstrStream.fire)
  
  val instrDispatcher = new Area {
    when(io.rawInstrStream.valid){
      switch(io.rawInstrStream.payload((conf.instrTotalWidth - 1) downto (conf.instrTotalWidth - instrBitWidth))){
        is(DedupCoreOp.WRITE2FREE.asBits){
          // go to waitInstrStream
          isNeededInstr := True
          io.waitingInstrStream.translateFrom(io.rawInstrStream){ (decodedInstr, rawBits) =>
            val decodedFullInstr = WRITE2FREEInstr(conf)
            WRITE2FREEInstr(conf).decodeFromRawBits()(decodedFullInstr, rawBits)
            decodedInstr.pageCount := decodedFullInstr.hostLBALen
            decodedInstr.opCode    := decodedFullInstr.opCode
            decodedInstr.tag       := tagGenerator.value
          }
          io.readyInstrStream.setIdle()
        }
        is(DedupCoreOp.ERASEREF.asBits){
          // go to readyInstrStream
          isNeededInstr := True
          io.readyInstrStream.translateFrom(io.rawInstrStream){ (decodedInstr, rawBits) =>
            val decodedFullInstr = ERASEREFInstr(conf)
            ERASEREFInstr(conf).decodeFromRawBits()(decodedFullInstr, rawBits)
            decodedInstr.assignSomeByName(decodedFullInstr)
            decodedInstr.tag     := tagGenerator.value
          }
          io.waitingInstrStream.setIdle()
        }
        is(DedupCoreOp.READSSD.asBits){
          // Throw
          isNeededInstr := False
          io.rawInstrStream.ready := True
          io.readyInstrStream.setIdle()
          io.waitingInstrStream.setIdle()
        }
        default{
          // Throw
          isNeededInstr := False
          io.rawInstrStream.ready := True
          io.readyInstrStream.setIdle()
          io.waitingInstrStream.setIdle()
        }
      }
    }.otherwise{
      // Throw
      isNeededInstr := False
      io.rawInstrStream.ready := True
      io.readyInstrStream.setIdle()
      io.waitingInstrStream.setIdle()
    }
  }
}