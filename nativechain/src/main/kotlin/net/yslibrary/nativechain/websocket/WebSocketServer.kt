package net.yslibrary.nativechain.websocket

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import io.ktor.application.install
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.ws
import io.ktor.features.CallLogging
import io.ktor.features.DefaultHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.FrameType
import io.ktor.http.cio.websocket.WebSocketSession
import io.ktor.http.cio.websocket.readText
import io.ktor.request.uri
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.error
import io.ktor.websocket.webSocket
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.channels.sendBlocking
import net.yslibrary.nativechain.Block
import net.yslibrary.nativechain.Nativechain
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Duration

class WebSocketServer(val nativechain: Nativechain, val moshi: Moshi) {

    private val logger = LoggerFactory.getLogger("WebSocketServer")

    private val websocketClient = HttpClient(CIO).config { install(WebSockets) }

    private val sockets = arrayListOf<Pair<Peer, WebSocketSession>>()
    fun sockets() = sockets.toList()

    private val messageJsonAdapter: JsonAdapter<Message> by lazy { moshi.adapter(Message::class.java) }

    private fun buildLatestMessage(): String =
        messageJsonAdapter.toJson(Message(type = MessageType.RESPONSE_BLOCK.ordinal, block = nativechain.getLatestBlock(), blockchain = null))

    private fun buildChainMessage(): String =
        messageJsonAdapter.toJson(Message(type = MessageType.RESPONSE_BLOCKCHAIN.ordinal, block = null, blockchain = nativechain.blockchain))

    private fun buildChainLengthMessage(): String =
        messageJsonAdapter.toJson(Message(type = MessageType.QUERY_LATEST.ordinal, block = null, blockchain = null))

    private fun buildAllMessage(): String =
        messageJsonAdapter.toJson(Message(type = MessageType.QUERY_ALL.ordinal, blockchain = null, block = null))

    fun connectToPeers(newPeers: List<Peer>) {
        newPeers.forEach { peer ->
            async {
                val uri = URI.create(peer.host)
                logger.debug("connecting to $peer, $uri")

                websocketClient.ws(method = HttpMethod.Get, host = uri.host, port = uri.port, path = uri.path) {
                    initConnection(peer, this)
                }
            }
        }
    }

    fun startP2PServer(port: Int) {
        embeddedServer(Netty, port) {
            install(DefaultHeaders)
            install(CallLogging)
            install(io.ktor.websocket.WebSockets) {
                pingPeriod = Duration.ofMinutes(1)
            }
            routing {
                webSocket {
                    val peer = Peer(this.call.request.uri)
                    logger.debug("received websocket connection: $peer")
                    initConnection(peer, this)
                }
            }
        }.start(wait = false)
    }

    private suspend fun initConnection(peer: Peer, session: WebSocketSession) {
        logger.debug("connection created: $peer")
        sockets += peer to session
        chainLengthMessage(session)

        try {
            session.incoming.consumeEach {
                logger.debug("message received from peer: $it")
                when (it.frameType) {
                    FrameType.TEXT -> handleMessage(session, (it as Frame.Text).readText())
                    else -> {
                        // no-op
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e)
        }
    }

    fun chainLengthMessage(session: WebSocketSession) {
        write(session, buildChainLengthMessage())
    }

    fun sendLatestMessage(session: WebSocketSession) {
        write(session, buildLatestMessage())
    }

    fun sendChainMessage(session: WebSocketSession) {
        write(session, buildChainMessage())
    }

    fun broadcastLatestMessage() {
        broadcast(buildLatestMessage())
    }

    fun broadcastAllMessage() {
        broadcast(buildAllMessage())
    }

    private fun write(session: WebSocketSession, message: String) {
        session.outgoing.sendBlocking(Frame.Text(message))
    }

    fun broadcast(message: String) {
        sockets.forEach { write(it.second, message) }
    }

    fun handleBlockchainResponse(receivedBlocks: List<Block>) {
        val latestBlockReceived = receivedBlocks.last()
        val latestBlockHeld = nativechain.getLatestBlock()
        if (latestBlockReceived.index > latestBlockHeld.index) {
            logger.info("received blockchain is ahead of ours. current head: ${latestBlockHeld.index}, received head: ${latestBlockReceived.index}")
            if (latestBlockHeld.hash == latestBlockReceived.previousHash) {
                logger.info("we have previous hash for the received chain")
                nativechain.addBlock(latestBlockReceived)
                broadcastLatestMessage()
            } else if (receivedBlocks.size == 1) {
                logger.info("we need to query the chain from our peers")
                broadcastAllMessage()
            } else {
                logger.debug("received blockchain is longer than ours")
                nativechain.replaceChain(receivedBlocks)
                broadcastLatestMessage()
            }
        } else {
            logger.info("received blockchain is behind ours. skipping process...")
        }
    }

    private fun handleMessage(from: WebSocketSession, json: String) {
        messageJsonAdapter.fromJson(json)?.let { message ->
            when (message.messageType()) {
                MessageType.QUERY_LATEST -> {
                    sendLatestMessage(from)
                }
                MessageType.QUERY_ALL -> {
                    sendChainMessage(from)
                }
                MessageType.RESPONSE_BLOCK -> {
                    handleBlockchainResponse(listOf(message.block!!))
                }
                MessageType.RESPONSE_BLOCKCHAIN -> {
                    handleBlockchainResponse(message.blockchain!!)
                }
            }
        }
    }
}

