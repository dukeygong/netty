/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.jboss.netty.handler.codec.replay;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.frame.FrameDecoder;

import java.net.SocketAddress;

/**
 * A specialized variation of {@link FrameDecoder} which enables implementation
 * of a non-blocking decoder in the blocking I/O paradigm.
 * <p>
 * The biggest difference between {@link ReplayingDecoder} and
 * {@link FrameDecoder} is that {@link ReplayingDecoder} allows you to
 * implement the {@code decode()} and {@code decodeLast()} methods just like
 * all required bytes were received already, rather than checking the
 * availability of the required bytes.  For example, the following
 * {@link FrameDecoder} implementation:
 * <pre>
 * public class IntegerHeaderFrameDecoder extends {@link FrameDecoder} {
 *
 *   {@code @Override}
 *   protected Object decode({@link ChannelHandlerContext} ctx,
 *                           {@link Channel} channel,
 *                           {@link ChannelBuffer} buf) throws Exception {
 *
 *     if (buf.readableBytes() &lt; 4) {
 *        return <strong>null</strong>;
 *     }
 *
 *     buf.markReaderIndex();
 *     int length = buf.readInt();
 *
 *     if (buf.readableBytes() &lt; length) {
 *        buf.resetReaderIndex();
 *        return <strong>null</strong>;
 *     }
 *
 *     return buf.readBytes(length);
 *   }
 * }
 * </pre>
 * is simplified like the following with {@link ReplayingDecoder}:
 * <pre>
 * public class IntegerHeaderFrameDecoder
 *      extends {@link ReplayingDecoder}&lt;{@link VoidEnum}&gt; {
 *
 *   protected Object decode({@link ChannelHandlerContext} ctx,
 *                           {@link Channel} channel,
 *                           {@link ChannelBuffer} buf,
 *                           {@link VoidEnum} state) throws Exception {
 *
 *     return buf.readBytes(buf.readInt());
 *   }
 * }
 * </pre>
 *
 * <h3>How does this work?</h3>
 * <p>
 * {@link ReplayingDecoder} passes a specialized {@link ChannelBuffer}
 * implementation which throws an {@link Error} of certain type when there's not
 * enough data in the buffer.  In the {@code IntegerHeaderFrameDecoder} above,
 * you just assumed that there will be 4 or more bytes in the buffer when
 * you call {@code buf.readInt()}.  If there's really 4 bytes in the buffer,
 * it will return the integer header as you expected.  Otherwise, the
 * {@link Error} will be raised and the control will be returned to
 * {@link ReplayingDecoder}.  If {@link ReplayingDecoder} catches the
 * {@link Error}, then it will rewind the {@code readerIndex} of the buffer
 * back to the 'initial' position (i.e. the beginning of the buffer) and call
 * the {@code decode(..)} method again when more data is received into the
 * buffer.
 * <p>
 * Please note that {@link ReplayingDecoder} always throws the same cached
 * {@link Error} instance to avoid the overhead of creating a new {@link Error}
 * and filling its stack trace for every throw.
 *
 * <h3>Limitations</h3>
 * <p>
 * At the cost of the simplicity, {@link ReplayingDecoder} enforces you a few
 * limitations:
 * <ul>
 * <li>Some buffer operations are prohibited.</li>
 * <li>Performance can be worse if the network is slow and the message
 *     format is complicated unlike the example above.  In this case, your
 *     decoder might have to decode the same part of the message over and over
 *     again.</li>
 * <li>You must keep in mind that {@code decode(..)} method can be called many
 *     times to decode a single message.  For example, the following code will
 *     not work:
 * <pre> public class MyDecoder extends {@link ReplayingDecoder}&lt;{@link VoidEnum}&gt; {
 *
 *   private final Queue&lt;Integer&gt; values = new LinkedList&lt;Integer&gt;();
 *
 *   {@code @Override}
 *   public Object decode(.., {@link ChannelBuffer} buffer, ..) throws Exception {
 *
 *     // A message contains 2 integers.
 *     values.offer(buffer.readInt());
 *     values.offer(buffer.readInt());
 *
 *     // This assertion will fail intermittently since values.offer()
 *     // can be called more than two times!
 *     assert values.size() == 2;
 *     return values.poll() + values.poll();
 *   }
 * }</pre>
 *      The correct implementation looks like the following, and you can also
 *      utilize the 'checkpoint' feature which is explained in detail in the
 *      next section.
 * <pre> public class MyDecoder extends {@link ReplayingDecoder}&lt;{@link VoidEnum}&gt; {
 *
 *   private final Queue&lt;Integer&gt; values = new LinkedList&lt;Integer&gt;();
 *
 *   {@code @Override}
 *   public Object decode(.., {@link ChannelBuffer} buffer, ..) throws Exception {
 *
 *     // Revert the state of the variable that might have been changed
 *     // since the last partial decode.
 *     values.clear();
 *
 *     // A message contains 2 integers.
 *     values.offer(buffer.readInt());
 *     values.offer(buffer.readInt());
 *
 *     // Now we know this assertion will never fail.
 *     assert values.size() == 2;
 *     return values.poll() + values.poll();
 *   }
 * }</pre>
 *     </li>
 * </ul>
 *
 * <h3>Improving the performance</h3>
 * <p>
 * Fortunately, the performance of a complex decoder implementation can be
 * improved significantly with the {@code checkpoint()} method.  The
 * {@code checkpoint()} method updates the 'initial' position of the buffer so
 * that {@link ReplayingDecoder} rewinds the {@code readerIndex} of the buffer
 * to the last position where you called the {@code checkpoint()} method.
 *
 * <h4>Calling {@code checkpoint(T)} with an {@link Enum}</h4>
 * <p>
 * Although you can just use {@code checkpoint()} method and manage the state
 * of the decoder by yourself, the easiest way to manage the state of the
 * decoder is to create an {@link Enum} type which represents the current state
 * of the decoder and to call {@code checkpoint(T)} method whenever the state
 * changes.  You can have as many states as you want depending on the
 * complexity of the message you want to decode:
 *
 * <pre>
 * public enum MyDecoderState {
 *   READ_LENGTH,
 *   READ_CONTENT;
 * }
 *
 * public class IntegerHeaderFrameDecoder
 *      extends {@link ReplayingDecoder}&lt;<strong>MyDecoderState</strong>&gt; {
 *
 *   private int length;
 *
 *   public IntegerHeaderFrameDecoder() {
 *     // Set the initial state.
 *     <strong>super(MyDecoderState.READ_LENGTH);</strong>
 *   }
 *
 *   {@code @Override}
 *   protected Object decode({@link ChannelHandlerContext} ctx,
 *                           {@link Channel} channel,
 *                           {@link ChannelBuffer} buf,
 *                           <b>MyDecoderState</b> state) throws Exception {
 *     switch (state) {
 *     case READ_LENGTH:
 *       length = buf.readInt();
 *       <strong>checkpoint(MyDecoderState.READ_CONTENT);</strong>
 *     case READ_CONTENT:
 *       ChannelBuffer frame = buf.readBytes(length);
 *       <strong>checkpoint(MyDecoderState.READ_LENGTH);</strong>
 *       return frame;
 *     default:
 *       throw new Error("Shouldn't reach here.");
 *     }
 *   }
 * }
 * </pre>
 *
 * <h4>Calling {@code checkpoint()} with no parameter</h4>
 * <p>
 * An alternative way to manage the decoder state is to manage it by yourself.
 * <pre>
 * public class IntegerHeaderFrameDecoder
 *      extends {@link ReplayingDecoder}&lt;<strong>{@link VoidEnum}</strong>&gt; {
 *
 *   <strong>private boolean readLength;</strong>
 *   private int length;
 *
 *   {@code @Override}
 *   protected Object decode({@link ChannelHandlerContext} ctx,
 *                           {@link Channel} channel,
 *                           {@link ChannelBuffer} buf,
 *                           {@link VoidEnum} state) throws Exception {
 *     if (!readLength) {
 *       length = buf.readInt();
 *       <strong>readLength = true;</strong>
 *       <strong>checkpoint();</strong>
 *     }
 *
 *     if (readLength) {
 *       ChannelBuffer frame = buf.readBytes(length);
 *       <strong>readLength = false;</strong>
 *       <strong>checkpoint();</strong>
 *       return frame;
 *     }
 *   }
 * }
 * </pre>
 *
 * <h3>Replacing a decoder with another decoder in a pipeline</h3>
 * <p>
 * If you are going to write a protocol multiplexer, you will probably want to
 * replace a {@link ReplayingDecoder} (protocol detector) with another
 * {@link ReplayingDecoder} or {@link FrameDecoder} (actual protocol decoder).
 * It is not possible to achieve this simply by calling
 * {@link ChannelPipeline#replace(ChannelHandler, String, ChannelHandler)}, but
 * some additional steps are required:
 * <pre>
 * public class FirstDecoder extends {@link ReplayingDecoder}&lt;{@link VoidEnum}&gt; {
 *
 *     public FirstDecoder() {
 *         super(true); // Enable unfold
 *     }
 *
 *     {@code @Override}
 *     protected Object decode({@link ChannelHandlerContext} ctx,
 *                             {@link Channel} ch,
 *                             {@link ChannelBuffer} buf,
 *                             {@link VoidEnum} state) {
 *         ...
 *         // Decode the first message
 *         Object firstMessage = ...;
 *
 *         // Add the second decoder
 *         ctx.getPipeline().addLast("second", new SecondDecoder());
 *
 *         // Remove the first decoder (me)
 *         ctx.getPipeline().remove(this);
 *
 *         if (buf.readable()) {
 *             // Hand off the remaining data to the second decoder
 *             return new Object[] { firstMessage, buf.readBytes(<b>super.actualReadableBytes()</b>) };
 *         } else {
 *             // Nothing to hand off
 *             return firstMessage;
 *         }
 *     }
 * </pre>
 *
 * @param <T>
 *        the state type; use {@link VoidEnum} if state management is unused
 *
 * @apiviz.landmark
 * @apiviz.has org.jboss.netty.handler.codec.replay.UnreplayableOperationException oneway - - throws
 */
public abstract class ReplayingDecoder<T extends Enum<T>>
        extends FrameDecoder {


    private final ReplayingDecoderBuffer replayable = new ReplayingDecoderBuffer(this);
    private T state;
    private int checkpoint;
    private boolean needsCleanup;


    /**
     * Creates a new instance with no initial state (i.e: {@code null}).
     */
    protected ReplayingDecoder() {
        this(null);
    }

    protected ReplayingDecoder(boolean unfold) {
        this(null, unfold);
    }

    /**
     * Creates a new instance with the specified initial state.
     */
    protected ReplayingDecoder(T initialState) {
        this(initialState, false);
    }

    protected ReplayingDecoder(T initialState, boolean unfold) {
        super(unfold);
        state = initialState;
    }

    @Override
    protected ChannelBuffer internalBuffer() {
        return super.internalBuffer();
    }

    /**
     * Stores the internal cumulative buffer's reader position.
     */
    protected void checkpoint() {
        ChannelBuffer cumulation = this.cumulation;
        if (cumulation != null) {
            checkpoint = cumulation.readerIndex();
        } else {
            checkpoint = -1; // buffer not available (already cleaned up)
        }
    }

    /**
     * Stores the internal cumulative buffer's reader position and updates
     * the current decoder state.
     */
    protected void checkpoint(T state) {
        checkpoint();
        setState(state);
    }

    /**
     * Returns the current state of this decoder.
     * @return the current state of this decoder
     */
    protected T getState() {
        return state;
    }

    /**
     * Sets the current state of this decoder.
     * @return the old state of this decoder
     */
    protected T setState(T newState) {
        T oldState = state;
        state = newState;
        return oldState;
    }

    /**
     * Decodes the received packets so far into a frame.
     *
     * @param ctx      the context of this handler
     * @param channel  the current channel
     * @param buffer   the cumulative buffer of received packets so far.
     *                 Note that the buffer might be empty, which means you
     *                 should not make an assumption that the buffer contains
     *                 at least one byte in your decoder implementation.
     * @param state    the current decoder state ({@code null} if unused)
     *
     * @return the decoded frame
     */
    protected abstract Object decode(ChannelHandlerContext ctx,
            Channel channel, ChannelBuffer buffer, T state) throws Exception;

    /**
     * Decodes the received data so far into a frame when the channel is
     * disconnected.
     *
     * @param ctx      the context of this handler
     * @param channel  the current channel
     * @param buffer   the cumulative buffer of received packets so far.
     *                 Note that the buffer might be empty, which means you
     *                 should not make an assumption that the buffer contains
     *                 at least one byte in your decoder implementation.
     * @param state    the current decoder state ({@code null} if unused)
     *
     * @return the decoded frame
     */
    protected Object decodeLast(
            ChannelHandlerContext ctx, Channel channel, ChannelBuffer buffer, T state) throws Exception {
        return decode(ctx, channel, buffer, state);
    }

    /**
     * Calls {@link #decode(ChannelHandlerContext, Channel, ChannelBuffer, Enum)}. This method
     * should be never used by {@link ReplayingDecoder} itself.  But to be safe we should handle it
     * anyway
     */
    @Override
    protected final Object decode(ChannelHandlerContext ctx, Channel channel, ChannelBuffer buffer) throws Exception {
        return decode(ctx, channel, buffer, state);
    }

    @Override
    protected final Object decodeLast(
            ChannelHandlerContext ctx, Channel channel, ChannelBuffer buffer) throws Exception {
        return decodeLast(ctx, channel, buffer, state);
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
            throws Exception {

        Object m = e.getMessage();
        if (!(m instanceof ChannelBuffer)) {
            ctx.sendUpstream(e);
            return;
        }

        ChannelBuffer input = (ChannelBuffer) m;
        if (!input.readable()) {
            return;
        }

        needsCleanup = true;

        if (cumulation == null) {
            // the cumulation buffer is not created yet so just pass the input
            // to callDecode(...) method
            cumulation = input;

            int oldReaderIndex = input.readerIndex();
            int inputSize = input.readableBytes();

            try {
                callDecode(
                        ctx, e.getChannel(),
                        input, replayable,
                        e.getRemoteAddress());
            } finally {
                int readableBytes = input.readableBytes();
                if (readableBytes > 0) {
                    int inputCapacity = input.capacity();
                    // check if readableBytes == capacity we can safe the copy as we will not be able to
                    // optimize memory usage anyway
                    boolean copy =
                            readableBytes != inputCapacity &&
                            inputCapacity > getMaxCumulationBufferCapacity();

                    // seems like there is something readable left in the input buffer
                    // or decoder wants a replay - create the cumulation buffer and
                    // copy the input into it
                    ChannelBuffer cumulation;
                    if (checkpoint > 0) {
                        int bytesToPreserve = inputSize - (checkpoint - oldReaderIndex);
                        if (copy) {
                            this.cumulation = cumulation = newCumulationBuffer(ctx, bytesToPreserve);
                            cumulation.writeBytes(input, checkpoint, bytesToPreserve);
                        } else {
                            this.cumulation = cumulation = input.slice(checkpoint, bytesToPreserve);
                        }
                    } else if (checkpoint == 0) {
                        if (copy) {
                            this.cumulation = cumulation = newCumulationBuffer(ctx, inputSize);
                            cumulation.writeBytes(input, oldReaderIndex, inputSize);
                            cumulation.readerIndex(input.readerIndex());
                        } else {
                            this.cumulation = cumulation = input.slice(oldReaderIndex, inputSize);
                            cumulation.readerIndex(input.readerIndex());
                        }
                    } else {
                        if (copy) {
                            this.cumulation = cumulation = newCumulationBuffer(ctx, input.readableBytes());
                            cumulation.writeBytes(input);
                        } else {
                            this.cumulation = cumulation = input;
                        }
                    }
                } else {
                    cumulation = null;
                }
            }
        } else {
            input = appendToCumulation(input);
            try {
                callDecode(ctx, e.getChannel(), input, replayable, e.getRemoteAddress());
            } finally {
                updateCumulation(ctx, input);
            }
        }
    }

    private void callDecode(
            ChannelHandlerContext context, Channel channel,
            ChannelBuffer input, ChannelBuffer replayableInput, SocketAddress remoteAddress) throws Exception {
        while (input.readable()) {
            int oldReaderIndex = checkpoint = input.readerIndex();
            Object result = null;
            T oldState = state;
            try {
                result = decode(context, channel, replayableInput, state);
                if (result == null) {
                    if (oldReaderIndex == input.readerIndex() && oldState == state) {
                        throw new IllegalStateException(
                                "null cannot be returned if no data is consumed and state didn't change.");
                    } else {
                        // Previous data has been discarded or caused state transition.
                        // Probably it is reading on.
                        continue;
                    }
                }
            } catch (ReplayError replay) {
                // Return to the checkpoint (or oldPosition) and retry.
                int checkpoint = this.checkpoint;
                if (checkpoint >= 0) {
                    input.readerIndex(checkpoint);
                } else {
                    // Called by cleanup() - no need to maintain the readerIndex
                    // anymore because the buffer has been released already.
                }
            }

            if (result == null) {
                // Seems like more data is required.
                // Let us wait for the next notification.
                break;
            }

            if (oldReaderIndex == input.readerIndex() && oldState == state) {
                throw new IllegalStateException(
                        "decode() method must consume at least one byte " +
                        "if it returned a decoded message (caused by: " +
                        getClass() + ')');
            }

            // A successful decode
            unfoldAndFireMessageReceived(context, remoteAddress, result);
        }
    }

    @Override
    protected void cleanup(ChannelHandlerContext ctx, ChannelStateEvent e)
            throws Exception {
        try {
            ChannelBuffer cumulation = this.cumulation;
            if (!needsCleanup) {
                return;
            }

            needsCleanup = false;
            replayable.terminate();

            if (cumulation != null && cumulation.readable()) {
                // Make sure all data was read before notifying a closed channel.
                callDecode(ctx, e.getChannel(), cumulation, replayable, null);
            }

            // Call decodeLast() finally.  Please note that decodeLast() is
            // called even if there's nothing more to read from the buffer to
            // notify a user that the connection was closed explicitly.
            Object partiallyDecoded = decodeLast(ctx, e.getChannel(), replayable, state);

            this.cumulation = null;

            if (partiallyDecoded != null) {
                unfoldAndFireMessageReceived(ctx, null, partiallyDecoded);
            }
        } catch (ReplayError replay) {
            // Ignore
        } finally {
            ctx.sendUpstream(e);
        }
    }
}
