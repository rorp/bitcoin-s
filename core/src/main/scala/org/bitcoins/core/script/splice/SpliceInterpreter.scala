package org.bitcoins.core.script.splice

import org.bitcoins.core.script.constant._
import org.bitcoins.core.script.result.ScriptErrorInvalidStackOperation
import org.bitcoins.core.script.{
  ExecutionInProgressScriptProgram,
  StartedScriptProgram
}

/** Created by chris on 2/4/16.
  */
sealed abstract class SpliceInterpreter {

  /** Pushes the string length of the top element of the stack (without popping
    * it).
    */
  def opSize(
      program: ExecutionInProgressScriptProgram): StartedScriptProgram = {
    require(program.script.headOption.contains(OP_SIZE),
            "Script top must be OP_SIZE")
    if (program.stack.nonEmpty) {
      if (program.stack.head == OP_0) {
        program.updateStackAndScript(OP_0 :: program.stack, program.script.tail)
      } else {
        val scriptNumber = program.stack.head match {
          case ScriptNumber.zero => ScriptNumber.zero
          case x: ScriptToken    => ScriptNumber(x.bytes.size)
        }
        program.updateStackAndScript(scriptNumber :: program.stack,
                                     program.script.tail)
      }
    } else {
      program.failExecution(ScriptErrorInvalidStackOperation)
    }
  }
}

object SpliceInterpreter extends SpliceInterpreter
