package org.scalacoin.script.splice

import org.scalacoin.script.ScriptProgramImpl
import org.scalacoin.script.bitwise.OP_EQUAL
import org.scalacoin.script.constant.{ScriptNumberImpl, OP_2, ScriptConstantImpl, OP_0}
import org.scalacoin.util.TestUtil
import org.scalatest.{MustMatchers, FlatSpec}

/**
 * Created by chris on 2/4/16.
 */
class SpliceInterpreterTest extends FlatSpec with MustMatchers with SpliceInterpreter {

  "SpliceInterpreter" must "evaluate an OP_SIZE correctly" in {
    val stack = List(OP_0)
    val script = List(OP_SIZE)
    val program = ScriptProgramImpl(stack,script,TestUtil.transaction,List())
    val newProgram = opSize(program)
    newProgram.stack must be (List(OP_0,OP_0))
    newProgram.script.isEmpty must be (true)

  }

  it must "evaluate an OP_SIZE correctly with 0x7f" in {
    val stack = List(ScriptConstantImpl("7f"))
    val script = List(OP_SIZE)
    val program = ScriptProgramImpl(stack,script,TestUtil.transaction,List())
    val newProgram = opSize(program)
    newProgram.stack must be (List(ScriptNumberImpl(1),ScriptConstantImpl("7f")))
    newProgram.script.isEmpty must be (true)
  }


  it must "evaluate an OP_SIZE correctly with a negative number" in {
    val stack = List(ScriptNumberImpl(-1))
    val script = List(OP_SIZE)
    val program = ScriptProgramImpl(stack,script,TestUtil.transaction,List())
    val newProgram = opSize(program)
    newProgram.stack must be (List(ScriptNumberImpl(1),ScriptNumberImpl(-1)))
    newProgram.script.isEmpty must be (true)
  }
}
