//> using scala "2.13.12"
//> using dep "org.chipsalliance::chisel:6.6.0"
//> using plugin "org.chipsalliance:::chisel-plugin:6.6.0"
//> using options "-unchecked", "-deprecation", "-language:reflectiveCalls", "-feature", "-Xcheckinit", "-Xfatal-warnings", "-Ywarn-dead-code", "-Ywarn-unused", "-Ymacro-annotations"

import chisel3._
import chisel3.util._
// _root_ disambiguates from package chisel3.util.circt if user imports chisel3.util._
import _root_.circt.stage.ChiselStage

class BitExtract(patIn: String) {
  val pat = patIn.replace("_", "").replace(" ", "")

  def apply(c: Char): (Int, Int) = {
    var first = pat.indexOf(c)
    var last = pat.lastIndexOf(c)
    if (pat.slice(first, last+1).exists(_ != c)) {
      throw new Exception("this type of pattern is not supported yet");
    }
    first = pat.length-first-1
    last = pat.length-last-1
    if (first > last) {
      (first, last)
    } else {
      (last, first)
    }
  }

  def apply(c: Char, v: UInt): UInt = {
    val p = apply(c)
    v(p._1, p._2)
  }
}

class AsyncMem extends Bundle {
  val have_req = Input(UInt(1.W))
  val req_iswr = Input(UInt(1.W))
  val req_addr = Input(UInt(32.W)) // byte addr
  val req_wrd = Input(UInt(32.W))

  val finished = Output(UInt(1.W)) // if set, data is available this tick
  val data = Output(UInt(32.W))
}

class SimpleAsyncMem(bytes: Int) extends Module {
  val io = IO(new AsyncMem())
  val mem = SyncReadMem(bytes/4, UInt(32.W))

  val mode = RegInit(0.U(2.W))
  val data0 = Reg(UInt(32.W))

  io.data := 0.U
  when (io.have_req === 1.U) {
    when (io.req_addr(1,0) === 0.U) {
      io.data := mem.readWrite(io.req_addr >> 2, io.req_wrd, io.have_req === 1.U, io.req_iswr === 1.U)
      mode := 2.U
    }
    .otherwise {
      data0 := mem.readWrite(io.req_addr >> 2, io.req_wrd, io.have_req === 1.U, io.req_iswr === 1.U)
      // unaligned writes are not allowed!
      when (mode === 0.U) {
        mode := 1.U
      }.otherwise {
        val data1 = mem.readWrite((io.req_addr >> 2) + 1.U, io.req_wrd, io.have_req === 1.U, io.req_iswr === 1.U)
        switch (io.req_addr(1,0)) {
          is (1.U) {
            io.data := Cat(data1(7,0), data0(31,8))
          }
          is (2.U) {
            io.data := Cat(data1(15,0), data0(31,16))
          }
          is (3.U) {
            io.data := Cat(data1(23,0), data0(31,24))
          }
        }
        mode := 2.U
      }
    }
  }

  when (mode === 2.U) {
    mode := 0.U
    io.finished := 1.U
  }
  .otherwise {
    io.finished := 0.U
  }
}

class Core() extends Module {
  val cpuid1 = "b0100000000001010"
  val cpuid2 = "b0000000000000000"
  val feat = "b0000000000000001"

  val io = IO(new Bundle {
    val reset = Input(UInt(1.W))
    val ram = Flipped(new AsyncMem())

    val core_flags = Output(UInt(4.W))
    val core_pc = Output(UInt(32.W))
    val core_regs = Output(Vec(8, UInt(32.W)))
  })

  val fl_z = Reg(UInt(1.W))
  val fl_n = Reg(UInt(1.W))
  val fl_c = Reg(UInt(1.W))
  val fl_v = Reg(UInt(1.W))

  val pc = Reg(UInt(32.W))
  val step = Reg(UInt(4.W))
  val gp = RegInit(VecInit(Seq.fill(8)(0.U(32.W))))
  val op_in = Reg(UInt(32.W))

  val have_4B_ops = Reg(UInt(1.W))

  val store_didld = RegInit(0.U(1.W))
  val store_didld_val = Reg(UInt(32.W))

  io.core_flags := Cat(fl_v, fl_c, fl_n, fl_z)
  io.core_pc := pc
  io.core_regs := gp

  io.ram.have_req := 0.U
  io.ram.req_iswr := 0.U
  io.ram.req_addr := 0.U
  io.ram.req_wrd := 0.U

  // TODO: support unaligned mem (and set feat bit)
  // warning: use store_didld ad store_didld_val
  def gen_write(s: UInt, addr: UInt, value: UInt, mem_ops_done: UInt) = {
    //val unaligned = arg(1,0) =/= 0.U
    val non32 = s =/= "b10".U
    when (non32) {
      // need load first
      when (store_didld === 1.U) {
        io.ram.have_req := 1.U
        io.ram.req_iswr := 1.U
        io.ram.req_addr := (addr >> 2) << 2 // remove low 2 bits

        val by0 = Wire(UInt(8.W))
        val by1 = Wire(UInt(8.W))
        val by2 = Wire(UInt(8.W))
        val by3 = Wire(UInt(8.W))
        by0 := store_didld_val(7,0)
        by1 := store_didld_val(15,8)
        by2 := store_didld_val(23,16)
        by3 := store_didld_val(31,24)

        switch (addr(1,0)) {
          is (0.U) {when (s === 0.U) {
              by0 := value(7,0)
            }.otherwise {
              by0 := value(7,0)
              by1 := value(15,8)
          }}
          is (1.U) {when (s === 0.U) {
              by1 := value(7,0)
            }.otherwise {
              by1 := value(7,0)
              by2 := value(15,8)
          }}
          is (2.U) {when (s === 0.U) {
              by2 := value(7,0)
            }.otherwise {
              by2 := value(7,0)
              by3 := value(15,8)
          }}
          is (3.U) {when (s === 0.U) {
              by3 := value(7,0)
            }.otherwise {
              // unaligned
          }}
        }

        io.ram.req_wrd := Cat(by3, by2, by1, by0)
        when (io.ram.finished === 1.U) {
          store_didld := 0.U
        }
        .otherwise {
          mem_ops_done := 0.U
        }
      }
      .otherwise {
        io.ram.have_req := 1.U
        io.ram.req_iswr := 0.U
        io.ram.req_addr := (addr >> 2) << 2 // remove low 2 bits
        io.ram.req_wrd := 0.U
        when (io.ram.finished === 1.U) {
          store_didld_val := io.ram.data
          store_didld := 1.U
        }
        mem_ops_done := 0.U
      }
    }
    .otherwise {
      io.ram.have_req := 1.U
      io.ram.req_iswr := 1.U
      io.ram.req_addr := (addr >> 2) << 2 // remove low 2 bits
      io.ram.req_wrd := value
      when (io.ram.finished === 1.U) {
      }
      .otherwise {
        // will always go into this branch until data received
        mem_ops_done := 0.U
      }
    }
  }

  when (io.reset === 1.U) {
    step := 0.U
    pc := 0x8000.U
    fl_z := 0.U
    fl_n := 0.U
    fl_c := 0.U
    fl_v := 0.U
    for (i <- 0 to 7) {
      gp(i) := 0.U
    }
    store_didld := 0.U
  }
  .otherwise {
    switch (step) {
      // wait for mem
      is (0.U) {
        io.ram.have_req := 1.U
        io.ram.req_iswr := 0.U
        io.ram.req_addr := pc
        io.ram.req_wrd := 0.U

        when (io.ram.finished === 1.U) {
          op_in := io.ram.data
          have_4B_ops := 1.U
          step := 1.U
        }
      }

      // exec
      is (1.U) {
        val op = Cat(op_in(7,0), op_in(15,8))

        //printf("current op: %b %b (%x %x)\n", op(15,8), op(7,0), op(15,8), op(7,0));
        val was2B_and_next = Wire(UInt(1.W))
        was2B_and_next := 0.U

        // simple computational instruction
        when (op === BitPat("b0???????????????")) {
          val pat = new BitExtract("i_ss_cccc ddd_xxxxx")
          val s = pat('s', op)
          val dest = pat('d', op)
          val opcode = pat('c', op)

          // 0-7 and 9 is sign_extend
          val input_sign_extend = Wire(UInt(1.W))
          input_sign_extend := !(opcode >> 3)
          when (9.U === opcode) {
            input_sign_extend := 1.U
          }

          val arg = Wire(UInt(32.W))
          when (pat('i', op) === 1.U) {
            val s = pat('x', op)
            when (input_sign_extend === 1.U) {
              arg := Cat(Fill(32-5, s(4)), s)
            } .otherwise {
              arg := Cat(Fill(32-5, 0.U), s)
            }
          } .otherwise {
            val pat2 = new BitExtract("sss_mm")
            arg := gp(pat2('s', op))
          }

          val out_value = Wire(UInt(33.W))
          out_value := 0.U
          val do_test = Wire(UInt(1.W))
          do_test := 0.U
          val mem_ops_done = Wire(UInt(1.W))
          mem_ops_done := 1.U
          switch (opcode) {
            is ("b0000".U) { // add
              out_value := Cat(Fill(1,0.U), gp(dest)) + Cat(Fill(1,0.U), arg)
              do_test := 1.U
            }
            is ("b0001".U) { // sub
              out_value := Cat(Fill(1,0.U), gp(dest)) + Cat(Fill(1,0.U), ~arg) + 1.U
              do_test := 1.U
            }
            is ("b0010".U) { // rsub
              out_value := Cat(Fill(1,0.U), arg) + Cat(Fill(1,0.U), ~gp(dest)) + 1.U
              do_test := 1.U
            }
            is ("b0011".U) { // cmp
              out_value := Cat(Fill(1,0.U), gp(dest)) + Cat(Fill(1,0.U), ~arg) + 1.U
              do_test := 1.U
            }
            is ("b0100".U) { // or
              out_value := Cat(Fill(1,0.U), gp(dest) | arg)
              do_test := 1.U
            }
            is ("b0101".U) { // xor
              out_value := Cat(Fill(1,0.U), gp(dest) ^ arg)
              do_test := 1.U
            }
            is ("b0110".U) { // and
              out_value := Cat(Fill(1,0.U), gp(dest) & arg)
              do_test := 1.U
            }
            is ("b0111".U) { // test
              out_value := Cat(Fill(1,0.U), gp(dest) & arg)
              do_test := 1.U
            }
            is ("b1000".U) { // movz
              out_value := Cat(Fill(1,0.U), arg)
              do_test := 1.U
            }
            is ("b1001".U) { // movs
              out_value := Cat(Fill(1,0.U), arg)
              do_test := 1.U
            }
            is ("b1010".U) { // load
              io.ram.have_req := 1.U
              io.ram.req_iswr := 0.U
              io.ram.req_addr := arg
              io.ram.req_wrd := 0.U
              when (io.ram.finished === 1.U) {
                out_value := io.ram.data
              }
              .otherwise {
                // will always go into this branch until data received
                out_value := 0.U
                mem_ops_done := 0.U
              }

              do_test := 0.U
            }
            is ("b1011".U) { // store
              gen_write(s, arg, gp(dest), mem_ops_done)

              out_value := 0.U
              do_test := 0.U
            }
            is ("b1100".U) {
              when (pat('i', op) === 1.U) { // slo
                out_value := Cat(Fill(1,0.U), (gp(dest) << 5) | arg(4,0))
                do_test := 0.U
              }
              .otherwise { // pop
                val sp = pat('x', op) >> 2

                io.ram.have_req := 1.U
                io.ram.req_iswr := 0.U
                io.ram.req_addr := gp(sp)
                io.ram.req_wrd := 0.U
                when (io.ram.finished === 1.U) {
                  out_value := io.ram.data
                  switch (s) {
                    is ("b00".U) { gp(sp) := gp(sp) + 1.U }
                    is ("b01".U) { gp(sp) := gp(sp) + 2.U }
                    is ("b10".U) { gp(sp) := gp(sp) + 4.U }
                  }
                }
                .otherwise {
                  // will always go into this branch until data received
                  out_value := 0.U
                  mem_ops_done := 0.U
                }

                do_test := 0.U
              }
            }
            is ("b1101".U) { // push
              // dest is sp
              val sz = Wire(UInt(3.W))
              sz := 0.U
              switch (s) {
                is ("b00".U) { sz := 1.U }
                is ("b01".U) { sz := 2.U }
                is ("b10".U) { sz := 4.U }
              }
              gen_write(s, gp(dest) - sz, arg, mem_ops_done)
              when (mem_ops_done === 1.U) {
                gp(dest) := gp(dest) - sz
              }

              out_value := 0.U
              do_test := 0.U
            }
            is ("b1110".U) { // readcr
              out_value := 0.U
              switch (arg) {
                is (0.U) { out_value := cpuid1.U }
                is (1.U) { out_value := cpuid2.U }
                is (2.U) { out_value := feat.U }
              }
              do_test := 0.U
            }
            is ("b1111".U) { // writecr
              out_value := 0.U
              do_test := 0.U
            }
          }

          when (do_test === 1.U) {
            def gen(width: Int) = {
              fl_z := out_value(width-1, 0) === 0.U
              fl_n := out_value(width-1)
              when ((opcode === BitPat("b00??")) && opcode =/= 0.U) {
                fl_c := !out_value(32)
              }.otherwise {
                fl_c := out_value(32,width) > 0.U
              }

              val moda = Wire(UInt(32.W))
              moda := gp(dest)
              val modb = Wire(UInt(32.W))
              modb := arg
              switch (opcode) {
                is ("b0001".U) { // sub
                  modb := ~arg
                }
                is ("b0010".U) { // rsub
                  moda := ~arg
                  modb := gp(dest)
                }
                is ("b0011".U) { // cmp
                  modb := ~arg
                }
              }

              fl_v := (moda(width-1) === modb(width-1)) && (out_value(width-1) =/= moda(width-1))
            }

            switch (s) {
              is ("b00".U) { gen(8) }
              is ("b01".U) { gen(16) }
              is ("b10".U) { gen(32) }
            }
          }

          when (mem_ops_done === 1.U) {
            when ((opcode =/= BitPat("b??11")) && (opcode =/= BitPat("b1101"))) { // save result
              when (opcode === BitPat("b1000")) { // movz: meaning zero extend
                def gen(width: Int) = {
                  gp(dest) := Cat(Fill(32-width, 0.U), out_value(width-1, 0))
                }

                switch (s) {
                  is ("b00".U) { gen(8) }
                  is ("b01".U) { gen(16) }
                  is ("b10".U) { gp(dest) := out_value }
                }
              }.otherwise { // sign extend
                def gen(width: Int) = {
                  gp(dest) := Cat(Fill(32-width, out_value(width-1)), out_value(width-1, 0))
                }

                switch (s) {
                  is ("b00".U) { gen(8) }
                  is ("b01".U) { gen(16) }
                  is ("b10".U) { gp(dest) := out_value }
                }
              }
            }

            pc := pc + 2.U
            step := 0.U // fetch next op
            was2B_and_next := 1.U
          }
        }

        // simple jump instruction
        when (op === BitPat("b100?????????????")) {
          val pat = new BitExtract("x_cccc dddddddd")
          val disp9 = Cat(pat('x',op), pat('d',op))
          val disp = Cat(Fill(32-9, disp9(8)), disp9)

          val cc = Wire(UInt(1.W))
          cc := 0.U
          switch (pat('c',op)) {
            is ("b0000".U) { cc := fl_z }
            is ("b0001".U) { cc := !fl_z }
            is ("b0010".U) { cc := fl_n }
            is ("b0011".U) { cc := !fl_n }
            is ("b0100".U) { cc := fl_c }
            is ("b0101".U) { cc := !fl_c }
            is ("b0110".U) { cc := fl_v }
            is ("b0111".U) { cc := !fl_v }
            is ("b1000".U) { cc := fl_c | fl_z }
            is ("b1001".U) { cc := !(fl_c | fl_z) }
            is ("b1010".U) { cc := fl_n =/= fl_v }
            is ("b1011".U) { cc := fl_n === fl_v }
            is ("b1100".U) { cc := fl_z | (fl_n =/= fl_v) }
            is ("b1101".U) { cc := (!fl_z) & (fl_n === fl_v) }
            is ("b1110".U) { cc := 1.U }
            is ("b1111".U) { cc := 0.U }
          }

          when (cc === 1.U) {
            pc := pc + disp
            when (disp === 0.U) {
              step := 1.U // exec next op
            }.otherwise {
              step := 0.U // fetch next op
            }
            was2B_and_next := 0.U
          }.otherwise {
            pc := pc + 2.U
            was2B_and_next := 1.U
            step := 0.U // fetch next op
          }
        }

        when ((was2B_and_next === 1.U) && (have_4B_ops === 1.U)) {
          val next = op_in >> 16
          // TODO: when next is noop, skip
          when ((next === BitPat("b0???????????????")) || (next === BitPat("b100?????????????"))) {
            step := 1.U // directly exec next op
            op_in := next
            have_4B_ops := 0.U
          }
        }
      }
    }
  }
}

class TestCPU() extends Module {
  val mem = Module(new SimpleAsyncMem(64*1024*1024)); // 64 MB
  val core = Module(new Core);

  val io = IO(new Bundle {
    val ram = new AsyncMem()

    val reset = Input(UInt(1.W))

    val core_flags = Output(UInt(4.W))
    val core_pc = Output(UInt(32.W))
    val core_regs = Output(Vec(8, UInt(32.W)))
  })

  io.core_flags := core.io.core_flags
  io.core_pc := core.io.core_pc
  io.core_regs := core.io.core_regs

  when (io.ram.have_req === 1.U) {
    io.ram <> mem.io;
    core.io.ram.finished := 0.U
    core.io.ram.data := 0.U
  }.otherwise {
    core.io.ram :<>= mem.io;
    io.ram.finished := 0.U
    io.ram.data := mem.io.data
  }

  core.io.reset := io.reset
}

object Main extends App {
  println(
    ChiselStage.emitSystemVerilog(
      gen = new TestCPU,
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
  )
}
