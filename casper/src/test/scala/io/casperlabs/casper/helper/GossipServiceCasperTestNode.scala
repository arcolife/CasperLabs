package io.casperlabs.casper.helper

import java.nio.file.Path

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import cats.temp.par.Par
import cats.mtl.DefaultApplicativeAsk
import com.google.protobuf.ByteString
import eu.timepit.refined._
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric._
import io.casperlabs.blockstorage._
import io.casperlabs.casper._
import io.casperlabs.casper.consensus
import io.casperlabs.casper.helper.BlockDagStorageTestFixture.mapSize
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.effect.implicits._
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm._
import io.casperlabs.comm.discovery.{Node, NodeDiscovery, NodeIdentifier}
import io.casperlabs.comm.gossiping._
import io.casperlabs.crypto.signatures.Ed25519
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.metrics.Metrics
import io.casperlabs.p2p.EffectsTestInstances._
import io.casperlabs.shared.PathOps.RichPath
import io.casperlabs.shared.{Cell, Log, Time}
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.atomic.AtomicInt
import monix.tail.Iterant

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.util.Random

class GossipServiceCasperTestNode[F[_]](
    local: Node,
    genesis: BlockMessage,
    sk: Array[Byte],
    blockDagDir: Path,
    blockStoreDir: Path,
    blockProcessingLock: Semaphore[F],
    faultToleranceThreshold: Float = 0f,
    shardId: String = "casperlabs",
    relaying: Relaying[F],
    gossipService: GossipServiceCasperTestNodeFactory.TestGossipService[F],
    validatorToNode: Map[ByteString, Node]
)(
    implicit
    concurrentF: Concurrent[F],
    blockStore: BlockStore[F],
    blockDagStorage: BlockDagStorage[F],
    timeEff: Time[F],
    metricEff: Metrics[F],
    casperState: Cell[F, CasperState],
    val logEff: LogStub[F]
) extends HashSetCasperTestNode[F](
      local,
      sk,
      genesis,
      blockDagDir,
      blockStoreDir
    )(concurrentF, blockStore, blockDagStorage, metricEff, casperState) {

  implicit val cliqueOracleEffect = SafetyOracle.cliqueOracle[F]

  //val defaultTimeout = FiniteDuration(1000, MILLISECONDS)

  val ownValidatorKey = validatorId match {
    case ValidatorIdentity(key, _, _) => ByteString.copyFrom(key)
  }

  implicit val casperEff: MultiParentCasperImpl[F] with HashSetCasperTestNode.AddBlockProxy[F] =
    new MultiParentCasperImpl[F](
      new MultiParentCasperImpl.StatelessExecutor(shardId),
      MultiParentCasperImpl.Broadcaster.fromGossipServices(Some(validatorId), relaying),
      Some(validatorId),
      genesis,
      shardId,
      blockProcessingLock,
      faultToleranceThreshold = faultToleranceThreshold
    ) with HashSetCasperTestNode.AddBlockProxy[F] {
      // Called in many ways:
      // - test proposes a block on the node that created it
      // - test tries to give a block created by node A to node B
      // - the download manager tries to validate a block
      override def addBlock(block: BlockMessage): F[BlockStatus] =
        if (block.sender == ownValidatorKey) {
          // The test is adding something this node created.
          super.addBlock(block)
        } else {
          // The test is adding something it created in another node's name,
          // i.e. it expects that dependencies will be requested.
          val sender  = validatorToNode(block.sender)
          val request = NewBlocksRequest(sender.some, List(block.blockHash))
          gossipService.newBlocks(request).map { response =>
            if (response.isNew) Processing else Valid
          }
        }

      override def superAddBlock(block: BlockMessage): F[BlockStatus] =
        super.addBlock(block)
    }

  /** Allow RPC calls intended for this node to be processed and enqueue responses. */
  def receive(): F[Unit] = gossipService.receive()

  /** Forget RPC calls intended for this node. */
  def clearMessages(): F[Unit] = gossipService.clearMessages()

  override def tearDownNode() =
    gossipService.shutdown >> super.tearDownNode()
}

trait GossipServiceCasperTestNodeFactory extends HashSetCasperTestNodeFactory {

  type TestNode[F[_]] = GossipServiceCasperTestNode[F]

  import HashSetCasperTestNode.peerNode
  import GossipServiceCasperTestNodeFactory._

  def standaloneF[F[_]](
      genesis: BlockMessage,
      transforms: Seq[TransformEntry],
      sk: Array[Byte],
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f
  )(
      implicit
      errorHandler: ErrorHandler[F],
      concurrentF: Concurrent[F],
      parF: Par[F],
      timerF: Timer[F]
  ): F[GossipServiceCasperTestNode[F]] = {
    val name               = "standalone"
    val identity           = peerNode(name, 40400)
    val logicalTime        = new LogicalTime[F]
    implicit val log       = new LogStub[F]()
    implicit val metricEff = new Metrics.MetricsNOP[F]
    implicit val nodeAsk   = makeNodeAsk(identity)(concurrentF)

    // Standalone, so nobody to relay to.
    val relaying = RelayingImpl(
      new TestNodeDiscovery[F](Nil),
      connectToGossip = _ => ???,
      relayFactor = 0,
      relaySaturation = 0
    )

    initStorage(genesis) flatMap {
      case (blockDagDir, blockStoreDir, blockDagStorage, blockStore) =>
        for {
          blockProcessingLock <- Semaphore[F](1)
          casperState         <- Cell.mvarCell[F, CasperState](CasperState())
          node = new GossipServiceCasperTestNode[F](
            identity,
            genesis,
            sk,
            blockDagDir,
            blockStoreDir,
            blockProcessingLock,
            faultToleranceThreshold,
            relaying = relaying,
            gossipService = new TestGossipService[F](name),
            validatorToNode = Map.empty // Shouldn't need it.
          )(
            concurrentF,
            blockStore,
            blockDagStorage,
            logicalTime,
            metricEff,
            casperState,
            log
          )
          _ <- node.initialize
        } yield node
    }
  }

  def networkF[F[_]](
      sks: IndexedSeq[Array[Byte]],
      genesis: BlockMessage,
      transforms: Seq[TransformEntry],
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f
  )(
      implicit errorHandler: ErrorHandler[F],
      concurrentF: Concurrent[F],
      parF: Par[F],
      timerF: Timer[F]
  ): F[IndexedSeq[GossipServiceCasperTestNode[F]]] = {
    val n     = sks.length
    val names = (0 to n - 1).map(i => s"node-$i")
    val peers = names.map(peerNode(_, 40400))

    var gossipServices = Map.empty[Node, TestGossipService[F]]

    val validatorToNode = peers
      .zip(sks)
      .map {
        case (node, sk) =>
          ByteString.copyFrom(Ed25519.toPublic(sk)) -> node
      }
      .toMap

    val nodesF = peers
      .zip(sks)
      .toList
      .traverse {
        case (peer, sk) =>
          val logicalTime = new LogicalTime[F]
          //implicit val log: Log[F] = new Log.NOPLog[F]()
          implicit val log       = new LogStub[F](peer.host)
          implicit val metricEff = new Metrics.MetricsNOP[F]
          implicit val nodeAsk   = makeNodeAsk(peer)(concurrentF)

          val gossipService = new TestGossipService[F](peer.host)
          gossipServices += peer -> gossipService

          // Simulate the broadcast semantics.
          val nodeDiscovery = new TestNodeDiscovery[F](peers.filterNot(_ == peer).toList)

          val connectToGossip: GossipService.Connector[F] =
            peer => gossipServices(peer).asInstanceOf[GossipService[F]].pure[F]

          val relaying = RelayingImpl(
            nodeDiscovery,
            connectToGossip = connectToGossip,
            relayFactor = peers.size - 1,
            relaySaturation = 100
          )

          initStorage(genesis) flatMap {
            case (blockDagDir, blockStoreDir, blockDagStorage, blockStore) =>
              for {
                semaphore <- Semaphore[F](1)
                casperState <- Cell.mvarCell[F, CasperState](
                                CasperState()
                              )
                node = new GossipServiceCasperTestNode[F](
                  peer,
                  genesis,
                  sk,
                  blockDagDir,
                  blockStoreDir,
                  semaphore,
                  faultToleranceThreshold,
                  relaying = relaying,
                  gossipService = gossipService,
                  validatorToNode = validatorToNode
                )(
                  concurrentF,
                  blockStore,
                  blockDagStorage,
                  logicalTime,
                  metricEff,
                  casperState,
                  log
                )
                _ <- gossipService.init(
                      node.casperEff,
                      blockStore,
                      relaying,
                      connectToGossip
                    )
              } yield node
          }
      }

    for {
      nodes <- nodesF
      _     <- nodes.traverse(_.initialize())
    } yield nodes.toVector
  }
}

object GossipServiceCasperTestNodeFactory {
  class TestNodeDiscovery[F[_]: Applicative](peers: List[Node]) extends NodeDiscovery[F] {
    def discover: F[Unit]                           = ???
    def lookup(id: NodeIdentifier): F[Option[Node]] = ???
    def alivePeersAscendingDistance: F[List[Node]]  = peers.pure[F]
  }

  def makeNodeAsk[F[_]](node: Node)(implicit ev: Applicative[F]) =
    new DefaultApplicativeAsk[F, Node] {
      val applicative: Applicative[F] = ev
      def ask: F[Node]                = node.pure[F]
    }

  /** Accumulate messages until receive is called by the test. */
  class TestGossipService[F[_]: Concurrent: Timer: Par: Log](host: String)
      extends GossipService[F] {

    /** Exercise the full underlying stack. It's what we are testing here, via the MultiParentCasper tests. */
    var underlying: GossipServiceServer[F] = _
    var shutdown: F[Unit]                  = ().pure[F]

    /** Casper is created a bit later then the TestGossipService instance. */
    def init(
        casper: MultiParentCasperImpl[F] with HashSetCasperTestNode.AddBlockProxy[F],
        blockStore: BlockStore[F],
        relaying: Relaying[F],
        connectToGossip: GossipService.Connector[F]
    ): F[Unit] = {
      for {
        downloadManagerR <- DownloadManagerImpl[F](
                             maxParallelDownloads = 10,
                             connectToGossip = connectToGossip,
                             backend = new DownloadManagerImpl.Backend[F] {
                               override def hasBlock(blockHash: ByteString): F[Boolean] =
                                 blockStore.contains(blockHash)

                               override def validateBlock(block: consensus.Block): F[Unit] =
                                 // Casper can only validate, store, but won't gossip because the Broadcaster we give it
                                 // will assume the DownloadManager will do that.
                                 // Doing this log here as it's evidently happened if we are here, and the tests expect it.
                                 Log[F].info(
                                   s"Requested missing block ${PrettyPrinter.buildString(block.blockHash)}"
                                 ) *>
                                   casper
                                     .superAddBlock(LegacyConversions.fromBlock(block)) flatMap {
                                   case Valid =>
                                     Log[F].debug(s"Validated and stored block ${PrettyPrinter
                                       .buildString(block.blockHash)}")

                                   case other =>
                                     Log[F].debug(s"Received invalid block ${PrettyPrinter
                                       .buildString(block.blockHash)}: $other")
                                     Sync[F].raiseError(
                                       new RuntimeException(s"Non-valid status: $other")
                                     )
                                 }

                               override def storeBlock(block: consensus.Block): F[Unit] =
                                 // Validation has already stored it.
                                 ().pure[F]

                               override def storeBlockSummary(
                                   summary: consensus.BlockSummary
                               ): F[Unit] =
                                 // No means to store summaries separately yet.
                                 ().pure[F]
                             },
                             relaying = relaying,
                             retriesConf = DownloadManagerImpl.RetriesConf.noRetries
                           ).allocated

        (downloadManager, downloadManagerShutdown) = downloadManagerR

        synchronizer = new SynchronizerImpl[F](
          connectToGossip = connectToGossip,
          backend = new SynchronizerImpl.Backend[F] {
            override def tips: F[List[ByteString]] =
              for {
                dag       <- casper.blockDag
                tipHashes <- casper.estimator(dag)
              } yield tipHashes.toList

            override def justifications: F[List[ByteString]] =
              // TODO: Presently there's no way to ask.
              List.empty.pure[F]

            override def validate(blockSummary: consensus.BlockSummary): F[Unit] =
              // TODO: Presently the Validation only works on full blocks.
              Log[F].debug(
                s"Trying to validate block summary ${PrettyPrinter.buildString(blockSummary.blockHash)}"
              )

            override def notInDag(blockHash: ByteString): F[Boolean] =
              blockStore.contains(blockHash).map(!_)
          },
          maxPossibleDepth = Int.MaxValue,
          minBlockCountToCheckBranchingFactor = Int.MaxValue,
          maxBranchingFactor = 2.0,
          maxDepthAncestorsRequest = Int.MaxValue
        )

        server <- GossipServiceServer[F](
                   backend = new GossipServiceServer.Backend[F] {
                     override def hasBlock(blockHash: ByteString): F[Boolean] =
                       blockStore.contains(blockHash)

                     override def getBlockSummary(
                         blockHash: ByteString
                     ): F[Option[consensus.BlockSummary]] = {
                       Log[F].debug(
                         s"Retrieving block summary ${PrettyPrinter.buildString(blockHash)} from storage."
                       )
                       blockStore
                         .get(blockHash)
                         .map(
                           _.map(mwt => LegacyConversions.toBlockSummary(mwt.getBlockMessage))
                         )
                     }

                     override def getBlock(blockHash: ByteString): F[Option[consensus.Block]] =
                       Log[F].debug(
                         s"Retrieving block ${PrettyPrinter.buildString(blockHash)} from storage."
                       ) *>
                         blockStore
                           .get(blockHash)
                           .map(_.map(mwt => LegacyConversions.toBlock(mwt.getBlockMessage)))
                   },
                   synchronizer = synchronizer,
                   downloadManager = downloadManager,
                   consensus = new GossipServiceServer.Consensus[F] {
                     override def onPending(dag: Vector[consensus.BlockSummary]) =
                       ().pure[F]
                     override def onDownloaded(blockHash: ByteString) =
                       // The validation already did what it had to.
                       Log[F].debug(s"Downloaded ${PrettyPrinter.buildString(blockHash)}")
                     override def listTips =
                       ???
                   },
                   // Not testing the genesis ceremony.
                   genesisApprover = new GenesisApprover[F] {
                     override def getCandidate = ???
                     override def addApproval(
                         blockHash: ByteString,
                         approval: consensus.Approval
                     )                          = ???
                     override def awaitApproval = ???
                   },
                   maxChunkSize = 1024 * 1024,
                   maxParallelBlockDownloads = 10
                 )
      } yield {
        underlying = server
        shutdown = downloadManagerShutdown
      }
    }

    /** The tests assume we are using the TransportLayer and messages are fire-and-forget.
      * The main use of `receives and `clearMessages` is to pass over blocks and to simulate
      * dropping the block on the receiver end.
      * The RPC works differently when it comes to pulling dependencies, it doesn't ask one by one,
      * and because the tests assume to know how many exactly messages get passed and calls `receive`
      * so many times. But we can preserve most of the spirit of the test by returning `true` here
      * (they assume broadcast) and not execute the underlying call if `clearMessages` is called;
      * but we can let the other methods work out on their own without suspension. We just have to
      * make sure that if the test calls `receive` then all the async calls finish before we return
      * to do any assertions. */
    val notificationQueue = Ref.unsafe[F, Queue[F[Unit]]](Queue.empty)

    /** Keep track of how many background operations (i.e. syncs and downloads) are running. */
    val asyncOpsCount = AtomicInt(0)

    implicit class RequestOps[T](req: F[T]) {
      def withAsyncOpsCount: F[T] =
        Sync[F].bracket(Sync[F].delay(asyncOpsCount.increment())) { _ =>
          req
        } { _ =>
          Sync[F].delay(asyncOpsCount.decrement())
        }
    }

    implicit class IterantOps[T](it: Iterant[F, T]) {
      def withAsyncOpsCount: Iterant[F, T] =
        Iterant.resource {
          Sync[F].delay(asyncOpsCount.increment())
        } { _ =>
          Sync[F].delay(asyncOpsCount.decrement())
        } flatMap { _ =>
          it
        }
    }

    /** With the TransportLayer this would mean the target node receives the full block and adds it.
      * We have to allow `newBlocks` to return for the original block to be able to finish adding,
      * so maybe we can return `true`, and call the underlying service later. But then we have to
      * allow it to play out all async actions, such as downloading blocks, syncing the DAG, etc. */
    def receive(): F[Unit] =
      for {
        notification <- notificationQueue.modify { q =>
                         q dequeueOption match {
                           case Some((notificaton, rest)) =>
                             rest -> notificaton
                           case None =>
                             q -> ().pure[F]
                         }
                       }
        _ <- notification
        _ <- awaitAsyncOps
      } yield ()

    /** With the TransportLayer this would mean the target node won't process a message.
      * For us it could mean that it receives the `newBlocks` notification but after that
      * we don't let it play out the async operations, for example by returning errors for
      * all requests it started. */
    def clearMessages(): F[Unit] =
      for {
        q <- notificationQueue.get
        _ <- Log[F].debug(s"Forgetting ${q.size} notifications.")
        _ <- notificationQueue.set(Queue.empty)
      } yield ()

    def awaitAsyncOps: F[Unit] = {
      def loop(): F[Unit] =
        Timer[F].sleep(250.millis) >>
          Sync[F].delay(asyncOpsCount.get) >>= {
          case 0 => ().pure[F]
          case _ => loop()
        }
      Concurrent.timeoutTo[F, Unit](loop(), 5.seconds, Sync[F].raiseError {
        new java.util.concurrent.TimeoutException("Still have async operations going!")
      })
    }

    override def newBlocks(request: NewBlocksRequest): F[NewBlocksResponse] =
      Log[F].info(
        s"Received notification about block ${PrettyPrinter.buildString(request.blockHashes.head)}"
      ) *>
        notificationQueue.update { q =>
          q enqueue underlying.newBlocks(request).void.withAsyncOpsCount
        } as {
        NewBlocksResponse(isNew = true)
      }

    override def getBlockChunked(request: GetBlockChunkedRequest): Iterant[F, Chunk] =
      Iterant
        .liftF(
          Log[F].info(
            s"Received request for block ${PrettyPrinter.buildString(request.blockHash)} Response sent."
          )
        )
        .flatMap { _ =>
          underlying.getBlockChunked(request).withAsyncOpsCount
        }

    override def streamAncestorBlockSummaries(
        request: StreamAncestorBlockSummariesRequest
    ): Iterant[F, consensus.BlockSummary] =
      Iterant
        .liftF(Log[F].info(s"Recevied request for ancestors of ${request.targetBlockHashes
          .map(PrettyPrinter.buildString(_))}"))
        .flatMap { _ =>
          underlying.streamAncestorBlockSummaries(request).withAsyncOpsCount
        }

    // The following methods are not tested in these suites.

    override def addApproval(request: AddApprovalRequest): F[Empty] = ???
    override def getGenesisCandidate(
        request: GetGenesisCandidateRequest
    ): F[consensus.GenesisCandidate] = ???
    override def streamDagTipBlockSummaries(
        request: StreamDagTipBlockSummariesRequest
    ): Iterant[F, consensus.BlockSummary] = ???
    override def streamBlockSummaries(
        request: StreamBlockSummariesRequest
    ): Iterant[F, consensus.BlockSummary] = ???
  }
}
