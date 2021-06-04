package io.iohk.metronome.checkpointing.interpreter

import io.iohk.metronome.checkpointing.models.{Block, Transaction, Ledger}
import io.iohk.metronome.checkpointing.models.CheckpointCertificate

/** `InterpreterRPC` is the interface that the Service can call on the Interpreter
  * side to send queries and commands. It provides RPC style methods for some of the
  * `InterpeterMessage` types, namely the ones `with Request with FromService`.
  *
  * See the `InterpreterMessage` for longer descriptions of the message types behind
  * the RPC facade.
  *
  * The return values are optional, so the Interpreter always has the option to not
  * send any response due to data availability issues, or just not being in a position
  * to produce an answer. For example if it's asked whether a block on a different fork
  * is valid, and it would have to roll back its current fork to execute the alternative
  * blocks that lead up to the one in question, it can decide that this is too expensive
  * and stay silent, until the federation decides that everyone has to switch.
  */
trait InterpreterRPC[F[_]] {

  def createBlockBody(
      ledger: Ledger,
      mempool: Seq[Transaction.ProposerBlock]
  ): F[Option[Block.Body]]

  def validateBlockBody(
      blockBody: Block.Body,
      ledger: Ledger
  ): F[Option[Boolean]]

  def newCheckpointCertificate(
      checkpointCertificate: CheckpointCertificate
  ): F[Unit]
}
