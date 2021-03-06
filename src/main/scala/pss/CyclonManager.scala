package pss

import java.util.UUID

import akka.actor._
import communication.Messages._
import group.{HybridGroupManager}
import peers.{DFPeer, Peer}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success}

class CyclonManager(private val _localPeer: Peer,
                    val initialView: mutable.HashMap[UUID, Peer],
                    val groupManager: HybridGroupManager = null) extends Actor {

  import config.Configs.CyclonManagerConfig._
  import config.Configs.SystemConfig._
  import system.dispatcher

  var localView: mutable.HashMap[UUID, Peer] = initialView
  var sentPeerData: ListBuffer[Peer] = ListBuffer[Peer]()
  def localPeer: Peer = _localPeer

  def insertSentToView(source: ListBuffer[Peer] = sentPeerData): Unit = {
    var peersToProcess: ListBuffer[Peer] = if(source == null) sentPeerData else source
    while (localView.size < localViewSize && peersToProcess.size > 0) {
      val tempPeer: Peer = peersToProcess(0)
      peersToProcess -= tempPeer

      //Discard entries pointing at self
      if(tempPeer != localPeer) {
        //If new peer already in view but is older, replace peer (refresh age). Otherwise, add peer to view
        val currentPeer = localView.get(tempPeer.uuid)
        currentPeer match {
          case Some(peer) =>
            if (peer.age > tempPeer.age) localView += (peer.uuid -> peer)
          case None =>
            localView += (tempPeer.uuid -> tempPeer)
        }
      }
    }
  }

  //Increase by on the age of all neighbours
  def ageGlobalView : Unit = {
    localView.values.foreach(peer => peer.asInstanceOf[DFPeer].increaseAgeByOne)
  }

  def getOlderGlobal: Peer = {
    var oldestPeer: Peer = null
    localView.values.foreach{ peer =>
      if (oldestPeer == null)
        oldestPeer = peer
      else
        oldestPeer = if (peer.age > oldestPeer.age) peer else oldestPeer
    }

    return oldestPeer
  }

  def getRandomGlobal(numberOfPeers: Int): ListBuffer[Peer] = {
    val globalPeersArray = scala.util.Random.shuffle(localView.values)
    var peerList = ListBuffer[Peer]()

    if (globalPeersArray.size <= numberOfPeers)
      peerList ++= globalPeersArray
    else
      peerList ++= globalPeersArray.slice(0, numberOfPeers)

    return peerList
  }

  //Select list of peers in view to send to neighbours
  def selectPeerInfoToDisseminate(target: Peer): Set[Peer] = {
    val localPeerInfo: Peer = localPeer.asInstanceOf[DFPeer].clone()
    var toDisseminate: ListBuffer[Peer] = ListBuffer[Peer]() //Mutable List

    //Shuffle to avoid duplicate dissemination cycles and conform to message size
    localView.values.foreach{ peer =>
      if (!peer.equals(target))
        toDisseminate += peer.asInstanceOf[DFPeer].clone()
    }

    toDisseminate = Random.shuffle(toDisseminate)

    while(toDisseminate.size >= gossipSize) {
      val dropPeer: Peer = toDisseminate.head
      toDisseminate -= dropPeer
      localView -= dropPeer.uuid
    }

    toDisseminate += localPeerInfo

    return toDisseminate.toSet
  }

  def sendMessageToPeer(peer: Peer, message: CyclonMessage): Unit = {
    getPeerActorRef(peer, "cyclon", context).onComplete{
       case Success(peerRef) =>
         peerRef ! message
       case Failure(f) =>
         println(f.getMessage)
    }
  }

  //Disseminate Cyclon information to selected peer (oldest peer in the view)
  def disseminateCyclonInfo: Unit = {
    insertSentToView()
    ageGlobalView

    val target: Peer = getOlderGlobal //Select neighbour Q with the highest age
    if (target != null) {
      localView -= target.uuid //Replace Q's entry with a new entry of age 0 and P's address
    }

    val toDisseminate: Set[Peer] = selectPeerInfoToDisseminate(target)

    sentPeerData = ListBuffer[Peer]()
    sentPeerData ++= toDisseminate.toList
    sentPeerData.filter(peer => peer != localPeer)

    val messageToSend = CyclonRequestMessage(localPeer, toDisseminate)
    sendMessageToPeer(target, messageToSend)
  }

  // Peer sends a message to himself every gossipInterval, and disseminates view info upon receival
  def scheduleMessageDissemination(localActor: ActorRef) = {
    system.scheduler.schedule(3000 milliseconds, gossipInterval, localActor, CyclonDisseminateMessage)
  }

  def processRequestMessage(message: CyclonRequestMessage): Unit = {
    val messagePeerInfo: ListBuffer[Peer] = ListBuffer[Peer]()
    messagePeerInfo ++= message.peerList.toList

    val peerInfoToDisseminate: Set[Peer] = selectPeerInfoToDisseminate(message.sender)
    val toFillEmptyView: ListBuffer[Peer] = ListBuffer[Peer]()

    peerInfoToDisseminate.foreach{ peer =>
      if(peer != localPeer)
        toFillEmptyView += peer.asInstanceOf[DFPeer].clone()
    }

    insertSentToView(messagePeerInfo)
    insertSentToView(toFillEmptyView)

    while(localView.size > localViewSize) localView -= getOlderGlobal.uuid
    val messageToSend = CyclonResponseMessage(localPeer, peerInfoToDisseminate)
    sendMessageToPeer(message.sender, messageToSend)
  }

  def processResponseMessage(message: CyclonResponseMessage): Unit = {
    val messagePeerInfo: ListBuffer[Peer] = ListBuffer[Peer]()
    messagePeerInfo ++= message.peerList.toList

    insertSentToView()
    insertSentToView(messagePeerInfo)

    //Remove oldest neighbour from view if view has surpassed its peer limit
    while(localView.size > localViewSize) {
      localView -= getOlderGlobal.uuid
    }

    if (groupManager != null) groupManager.refreshGroup(message.peerList.toList);
    sentPeerData = ListBuffer[Peer]()
  }

  def retrievePeerInfo(target: ActorRef, infoSize: Int): Unit = {
    val messagePeerInfo = getRandomGlobal(infoSize)

    target ! PeerInfoResponse(messagePeerInfo)
  }

  override def receive: Receive = {
    case msg: CyclonManagerStartMessage =>
      scheduleMessageDissemination(msg.destination)
    case CyclonDisseminateMessage =>
      disseminateCyclonInfo
    case msg: CyclonRequestMessage =>
      processRequestMessage(msg)
    case msg: CyclonResponseMessage =>
      processResponseMessage(msg)
    case msg: PeerInfoRequest =>
      retrievePeerInfo(sender, msg.infoSize)
    case _ =>
      println("Unrecognized message")
  }
}