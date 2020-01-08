package io.casperlabs.casper.highway

import cats._
import cats.data.{EitherT, WriterT}
import cats.implicits._
import cats.effect.{Clock, Sync}
import cats.effect.concurrent.Ref
import com.google.protobuf.ByteString
import java.time.Instant
import io.casperlabs.casper.consensus.{Block, BlockSummary, Era}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS}
import io.casperlabs.models.Message
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.dag.{DagRepresentation, DagStorage}
import io.casperlabs.storage.era.EraStorage
import io.casperlabs.casper.util.DagOperations
import scala.util.Random

/** Class to encapsulate the message handling logic of messages in an era.
  *
  * It can create blocks/ballots and persist them, even new eras,
  * but it should not have externally visible side effects, i.e.
  * not communicate over the network.
  *
  * Persisting messages is fine: if we want to handle messages concurrently
  * we have to keep track of the validator sequence number and make sure
  * we cite our own previous messages.
  *
  * The handler methods will return a list of domain events,
  * and a list of future actions in the form of an ADT;
  * it's up to the supervisor protocol to broadcast them
  * and schedule followup actions.
  *
  * This should make testing easier: the return values are not opaque.
  */
class EraRuntime[F[_]: MonadThrowable: Clock: EraStorage](
    conf: HighwayConf,
    val era: Era,
    leaderFunction: LeaderFunction,
    roundExponentRef: Ref[F, Int],
    maybeMessageProducer: Option[MessageProducer[F]],
    // Indicate whether the initial syncing mechanism that runs when the node is started
    // is still ongoing, or it has concluded and the node is caught up with its peers.
    // Before that, responding to messages risks creating an equivocation, in case some
    // state was somehow lost after a restart. It could also force others to deal with
    // messages build on blocks long gone and check a huge swathe of the DAG for merge
    // conflicts.
    isSynced: => F[Boolean],
    dag: DagRepresentation[F],
    // Random number generator used to pick the omega delay.
    rng: Random = new Random()
) {
  import EraRuntime.Agenda, Agenda._
  import EraRuntime.isCrossing

  type HWL[A] = HighwayLog[F, A]
  private val noop = HighwayLog.unit[F]

  val startTick = Ticks(era.startTick)
  val endTick   = Ticks(era.endTick)
  val start     = conf.toInstant(startTick)
  val end       = conf.toInstant(endTick)

  val bookingBoundaries =
    conf.criticalBoundaries(start, end, delayDuration = conf.bookingDuration)

  val keyBoundaries =
    conf.criticalBoundaries(start, end, delayDuration = conf.keyDuration)

  /** When we handle an incoming block or create a new one we may have to do additional work:
    * - if the block is a booking block, we have to execute the auction to pick the validators for the upcoming era
    * - when see a switch block, we have to look for a main ancestor which was a key block, to decide which era it belongs to.
    * These blocks can be identified by their round ID being after a boundary (a deadline),
    * while their main parent was still before the deadline.
    */
  private def isBoundary(boundaries: List[Instant])(
      mainParentBlockRound: Instant,
      blockRound: Instant
  ) = boundaries.exists(isCrossing(_)(mainParentBlockRound, blockRound))

  val isBookingBoundary = isBoundary(bookingBoundaries)(_, _)
  val isKeyBoundary     = isBoundary(keyBoundaries)(_, _)

  /** Switch blocks are the first blocks which are created _after_ the era ends.
    * They are still created by the validators of this era, and they signal the
    * end of the era. Switch blocks are what the child era is going to build on,
    * however, the validators of _this_ era are the ones that can finalize it by
    * building ballots on top of it. They cannot build more blocks on them though.
    */
  val isSwitchBoundary = isCrossing(end)(_, _)

  private implicit class MessageOps(msg: Message) {
    def roundInstant = conf.toInstant(Ticks(msg.roundId))
  }

  /** Current tick based on wall clock time. */
  private def currentTick =
    Clock[F].realTime(conf.tickUnit).map(Ticks(_))

  /** Check if the era is finished yet, including the post-era voting period. */
  private def isOverAt(tick: Ticks): F[Boolean] =
    if (tick < endTick) false.pure[F]
    else {
      conf.postEraVotingDuration match {
        case HighwayConf.VotingDuration.FixedLength(duration) =>
          (end plus duration).isBefore(conf.toInstant(tick)).pure[F]

        case HighwayConf.VotingDuration.SummitLevel(_) =>
          ???
      }
    }

  /** Calculate the beginning and the end of this round,
    * based on the current tick and the round length. */
  private def roundBoundariesAt(tick: Ticks): F[(Ticks, Ticks)] =
    // TODO (round_parameter_storage): We should be able to tell about each past tick what the exponent at the time was.
    roundExponentRef.get map { exp =>
      // The era start may not be divisible by the round length.
      // We should be okay to use `Ticks.roundBoundaries` here if we follow the
      // rules of when we can update the round exponent, that is, when the higher
      // and lower speeds align themselves.
      Ticks.roundBoundaries(startTick, exp)(tick)
    }

  /** Only produce messages once we are synced. */
  private def ifSynced(block: HWL[Unit]): HWL[Unit] =
    HighwayLog
      .liftF(isSynced)
      .ifM(
        block,
        HighwayLog.unit[F]
      )

  private def createLambdaResponse(
      messageProducer: MessageProducer[F],
      // TODO (NODE-1102): Lambda message will be a ballot during voting.
      lambdaMessage: Message.Block
  ): HWL[Unit] = ifSynced {
    for {
      b <- HighwayLog.liftF {
            messageProducer.ballot(
              eraId = era.keyBlockHash,
              roundId = Ticks(lambdaMessage.roundId),
              target = lambdaMessage.messageHash,
              // TODO (NODE-1102): Fetch our own last message ID.
              justifications =
                Map(PublicKey(lambdaMessage.validatorId) -> Set(lambdaMessage.messageHash))
            )
          }
      _ <- HighwayLog.tell[F](
            HighwayEvent.CreatedLambdaResponse(b)
          )
    } yield ()
  }

  private def createLambdaMessage(
      messageProducer: MessageProducer[F],
      roundId: Ticks
  ): HWL[Unit] = ifSynced {
    for {
      b <- HighwayLog.liftF {
            // TODO (NODE-1102): Create ballot during voting-only.
            messageProducer.block(
              eraId = era.keyBlockHash,
              roundId = roundId,
              // TODO (NODE-1102): Execute the fork choice.
              mainParent = ByteString.EMPTY,
              // TODO (NODE-1102): Get all justifications
              justifications = Map.empty,
              // TODO (NODE-1102): Detect boundary based on main parent
              isBookingBlock = false
            )
          }
      _ <- HighwayLog.tell[F](
            HighwayEvent.CreatedLambdaMessage(b)
          )
    } yield ()
  }

  private def createOmegaMessage(
      messageProducer: MessageProducer[F],
      roundId: Ticks
  ): HWL[Unit] = ifSynced {
    for {
      b <- HighwayLog.liftF {
            messageProducer.ballot(
              eraId = era.keyBlockHash,
              roundId = roundId,
              // TODO (NODE-1102): Execute the fork choice.
              target = ByteString.EMPTY,
              // TODO (NODE-1102): Get all justifications
              justifications = Map.empty
            )
          }
      _ <- HighwayLog.tell[F](
            HighwayEvent.CreatedOmegaMessage(b)
          )
    } yield ()
  }

  /** Trace the lineage of the switch block back to find a key block,
    * then the corresponding booking block.
    */
  private def createEra(
      switchBlock: Message
  ): HWL[Unit] = {
    val keyBlockBoundary     = end minus conf.keyDuration
    val bookingBlockBoundary = end minus conf.bookingDuration

    val childEra: F[Era] = for {
      keyBlock     <- findBlockCrossingBoundary(switchBlock, isCrossing(keyBlockBoundary))
      bookingBlock <- findBlockCrossingBoundary(keyBlock, isCrossing(bookingBlockBoundary))
      magicBits <- DagOperations
                    .bfTraverseF(List(keyBlock)) { msg =>
                      dag.lookupUnsafe(msg.parentBlock).map(List(_))
                    }
                    .takeUntil(_.messageHash == bookingBlock.messageHash)
                    .map(_.blockSummary.getHeader.magicBit)
                    .toList
                    .map(_.reverse)

      childEra = Era(
        parentKeyBlockHash = era.keyBlockHash,
        keyBlockHash = keyBlock.messageHash,
        bookingBlockHash = bookingBlock.messageHash,
        startTick = conf.toTicks(end),
        endTick = conf.toTicks(conf.eraEnd(end)),
        bonds = bookingBlock.blockSummary.getHeader.getState.bonds,
        leaderSeed =
          ByteString.copyFrom(LeaderSequencer.seed(era.leaderSeed.toByteArray, magicBits))
      )
      _ <- EraStorage[F].addEra(childEra)
    } yield childEra

    HighwayLog.liftF(childEra) map (HighwayEvent.CreatedEra(_)) flatMap (HighwayLog.tell[F](_))
  }

  /** Find a block in the ancestry where the parent is before a time,
    * but the block is after the time. */
  private def findBlockCrossingBoundary(
      descendant: Message,
      isBoundary: (Instant, Instant) => Boolean
  ): F[Message] = {
    def loop(child: Message, childTime: Instant): F[Message] =
      dag.lookupUnsafe(child.parentBlock).flatMap { parent =>
        val parentTime = parent.roundInstant
        if (isBoundary(parentTime, childTime))
          child.pure[F]
        else
          loop(parent, parentTime)
      }
    loop(descendant, descendant.roundInstant)
  }

  /** Pick a time during the round to send the omega message. */
  private def chooseOmegaTick(roundStart: Ticks, roundEnd: Ticks): Ticks = {
    val r = rng.nextDouble()
    val o = conf.omegaMessageTimeStart + r * (conf.omegaMessageTimeEnd - conf.omegaMessageTimeStart)
    val t = roundStart + o * (roundEnd - roundStart)
    Ticks(t.toLong)
  }

  /** Preliminary check before the block is executed. Invalid blocks can be dropped. */
  def validate(message: Message): EitherT[F, String, Unit] = {
    val ok = EitherT.rightT[F, String](())

    def checkF(c: F[Boolean], msg: String) = EitherT {
      c.ifM(ok.value, msg.asLeft[Unit].pure[F])
    }
    def check(c: Boolean, msg: String) = checkF(c.pure[F], msg)

    check(
      !maybeMessageProducer.map(_.validatorId).contains(message.validatorId),
      "The block is coming from a doppelganger."
    ) >> {
      message match {
        case b: Message.Block =>
          val roundId = Ticks(b.roundId)
          check(
            (leaderFunction(roundId) == b.validatorId),
            "The block is not coming from the leader of the round."
          )
        // TODO (NODE-1102): Check that we haven't received a block from the same validator in this round.
        case _ =>
          ok
      }
    }
  }

  /** Produce a starting agenda, depending on whether the validator is bonded or not. */
  def initAgenda: F[Agenda] =
    maybeMessageProducer.fold(Agenda.empty.pure[F]) { _ =>
      currentTick flatMap { tick =>
        isOverAt(tick).ifM(
          Agenda.empty.pure[F],
          roundBoundariesAt(tick) flatMap {
            case (from, to) =>
              val roundId = if (from >= tick) from else to
              Agenda(roundId -> StartRound(roundId)).pure[F]
          }
        )
      }
    }

  /** Handle a block or ballot coming from another validator. For example:
    * - if it's a lambda message, create a lambda response
    * - if it's a switch block, create a new era, unless it exists already.
    * Returns a list of events that happened during the persisting of changes.
    * This method is always called as a reaction to an incoming message,
    * so it doesn't return a future agenda of its own.
    */
  def handleMessage(message: Message): HWL[Unit] = {
    def illegal(msg: String) =
      MonadThrowable[HWL].raiseError[Unit](new IllegalStateException(msg))

    message match {
      // TODO (NODE-1102): Respond to lambda-ballots during voting.
      case _: Message.Ballot =>
        noop
      case b: Message.Block =>
        val roundId = Ticks(b.roundId)

        val response = maybeMessageProducer.fold(noop) { mp =>
          if (mp.validatorId == b.validatorId) {
            illegal("Shouldn't receive our own messages!")
          } else if (b.keyBlockHash != era.keyBlockHash) {
            illegal("Shouldn't receive messages from other eras!")
          } else if (leaderFunction(roundId) != b.validatorId) {
            // These blocks should fail validation and not be passed here.
            illegal("Shouldn't try to handle messages from non-leaders!")
          } else {
            // It's okay not to send a response to a message where we *did* participate
            // in the round it belongs to, but we moved on to a newer round.
            HighwayLog.liftF(currentTick.flatMap(roundBoundariesAt)) flatMap {
              case (from, _) if from == roundId =>
                createLambdaResponse(mp, b)
              case _ =>
                noop
            }
          }
        }

        response >> handleCriticalBlocks(b)
    }
  }

  /** Handle something that happens during a round:
    * - in rounds when we are leading, create a lambda message
    * - if it's a booking block, execute the auction
    * - if the main parent is a switch block, create a ballot instead
    * - sometime through the round, create an omega message
    * - if we're beyond the voting period after the end of the era, stop.
    *
    * Returns a list of events that took place, as well as its future agenda,
    * telling the caller (a supervisor) when it has to be scheduled again.
    */
  def handleAgenda(action: Agenda.Action): HWL[Agenda] =
    action match {
      case Agenda.StartRound(roundId) =>
        // Only create the agenda at the end, so if it takes too long to produce
        // a block we schedule the *next* round, possibly skipping a bunch.
        def agenda =
          for {
            now                           <- currentTick
            (currentRoundId, nextRoundId) <- roundBoundariesAt(now)
          } yield {
            val next = Agenda(nextRoundId -> Agenda.StartRound(nextRoundId))
            val omega = if (currentRoundId == roundId) {
              val omegaTick = chooseOmegaTick(roundId, nextRoundId)
              Agenda(omegaTick -> Agenda.CreateOmegaMessage(roundId))
            } else Agenda.empty

            omega ++ next
          }

        maybeMessageProducer.fold(noop) { mp =>
          if (leaderFunction(roundId) == mp.validatorId)
            createLambdaMessage(mp, roundId)
          else
            noop
        } >> HighwayLog.liftF(agenda)

      case Agenda.CreateOmegaMessage(roundId) =>
        maybeMessageProducer.fold(noop) {
          createOmegaMessage(_, roundId)
        } >> HighwayLog.liftF(Agenda.empty.pure[F])
    }

  private def handleCriticalBlocks(block: Message.Block): HWL[Unit] =
    if (block.parentBlock.isEmpty) noop
    else {
      for {
        parent <- HighwayLog.liftF[F, Message] {
                   dag.lookupUnsafe(block.parentBlock)
                 }
        parentTime = parent.roundInstant
        childTime  = block.roundInstant
        _          <- createEra(block).whenA(isSwitchBoundary(parentTime, childTime))
      } yield ()
    }
}

object EraRuntime {

  def fromGenesis[F[_]: Sync: Clock: DagStorage: EraStorage](
      conf: HighwayConf,
      genesis: BlockSummary,
      maybeMessageProducer: Option[MessageProducer[F]],
      initRoundExponent: Int,
      isSynced: => F[Boolean],
      leaderSequencer: LeaderSequencer = LeaderSequencer
  ): F[EraRuntime[F]] = {
    val era = Era(
      keyBlockHash = genesis.blockHash,
      bookingBlockHash = genesis.blockHash,
      startTick = conf.toTicks(conf.genesisEraStart),
      endTick = conf.toTicks(conf.genesisEraEnd),
      bonds = genesis.getHeader.getState.bonds
    )
    fromEra[F](conf, era, maybeMessageProducer, initRoundExponent, isSynced, leaderSequencer)
  }

  def fromEra[F[_]: Sync: Clock: DagStorage: EraStorage](
      conf: HighwayConf,
      era: Era,
      maybeMessageProducer: Option[MessageProducer[F]],
      initRoundExponent: Int,
      isSynced: => F[Boolean],
      leaderSequencer: LeaderSequencer = LeaderSequencer
  ): F[EraRuntime[F]] =
    for {
      leaderFunction   <- leaderSequencer[F](era)
      roundExponentRef <- Ref.of[F, Int](initRoundExponent)
      dag              <- DagStorage[F].getRepresentation
    } yield {
      new EraRuntime[F](
        conf,
        era,
        leaderFunction,
        roundExponentRef,
        // Whether the validator is bonded depends on the booking block. Only bonded validators
        // have to produce blocks and ballots in the era.
        maybeMessageProducer.filter { mp =>
          era.bonds.exists(b => b.validatorPublicKey == mp.validatorId)
        },
        isSynced,
        dag
      )
    }

  /** List of future actions to take. */
  type Agenda = Vector[Agenda.DelayedAction]

  object Agenda {
    sealed trait Action

    /** What action to take and when. */
    case class DelayedAction(tick: Ticks, action: Action)

    /** Handle one round:
      * - in rounds when we are leading, create a lambda message
      * - if it's a booking block, execute the auction
      * - if the main parent is a switch block, create a ballot instead
      * - if we're beyond the voting period after the end of the era, stop.
      */
    case class StartRound(roundId: Ticks) extends Action

    /** Create an Omega message, some time during the round */
    case class CreateOmegaMessage(roundId: Ticks) extends Action

    def apply(
        actions: (Ticks, Action)*
    ): Agenda =
      actions.map(DelayedAction.tupled).toVector

    val empty = apply()
  }

  /** Check that a parent timestamp is before, while the child is at or after a given boundary. */
  private def isCrossing(boundary: Instant)(parent: Instant, child: Instant): Boolean =
    parent.isBefore(boundary) && !child.isBefore(boundary)
}
