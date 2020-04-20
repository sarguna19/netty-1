package io.netty.example.telnet;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.util.BufferRecycler;
import com.fasterxml.jackson.databind.MappingJsonFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.io.IOException;
import java.util.List;

import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON RPC 1.0 compatible decoder capable of decoding JSON messages from a TCP stream.
 * The stream is framed first by inspecting the json for valid end marker (left curly)
 * and is passed to a Json parser (jackson) for converting into an object model.
 *
 * <p>There are no JSON parsers that I am aware of that does non blocking parsing.
 * This approach avoids having to run json parser over and over again on the entire
 * stream waiting for input. Parser is invoked only when we know of a full JSON message
 * in the stream.
 */
public class CliDecoder extends ByteToMessageDecoder {
    private static final Logger LOG = LoggerFactory.getLogger(CliDecoder.class);
    private static final JsonFactory JSON_FACTORY = new MappingJsonFactory();

    private final int maxFrameLength;
    //Indicates if the frame limit warning was issued
    private boolean maxFrameLimitWasReached = false;

    private final IOContext jacksonIOContext = new IOContext(new BufferRecycler(), null, false);

    // context for the previously read incomplete records
    private int lastRecordBytes = 0;
    private boolean inS = false;
    private int initialConnect =0;

    private int recordsRead;

    public CliDecoder(final int maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx, final ByteBuf buf, final List<Object> out)
            throws IOException {

            if (lastRecordBytes == 0) {
                if (buf.readableBytes() < 4) {
                    return; //wait for more data
                }

                skipSpaces(buf);

                byte[] buff = new byte[4];
                buf.getBytes(buf.readerIndex(), buff);
            }

            int index = lastRecordBytes + buf.readerIndex();
            for (; index <= buf.writerIndex(); index++) {

                switch (buf.getByte(index)) {
                    case '>':
                        if(buf.getByte(index-1) == '1' && buf.getByte(index-2) == 'R' ){
                            inS = !inS;
                        }
                    default:
                        break;
                }



                if (inS && initialConnect >0) {
                    ByteBuf slice = buf.readSlice(1+index - buf.readerIndex());
                    out.add(slice.toString(CharsetUtil.US_ASCII));
                    lastRecordBytes = 0;
                    inS = false ;
                    recordsRead++;
                    break;
                }
                else if(inS && initialConnect == 0) {
                    //initial Connect ignore
                    buf.clear();
                    initialConnect++;
                    lastRecordBytes = 0;
                    inS = false ;
                    break;
                }

                if (index - buf.readerIndex() >= maxFrameLength && !maxFrameLimitWasReached) {
                    maxFrameLimitWasReached = true;
                    System.out.println("***** Frame limit of {} bytes has been reached! *****"+ this.maxFrameLength);
                }
            }

            // end of stream, save the incomplete record index to avoid reexamining the whole on next run
            if (index >= buf.writerIndex()) {
                lastRecordBytes = buf.readableBytes();
            }

    }

    public int getRecordsRead() {
        return recordsRead;
    }

    private static void skipSpaces(final ByteBuf byteBuf) throws IOException {
        while (byteBuf.isReadable()) {
            int ch = byteBuf.getByte(byteBuf.readerIndex()) & 0xFF;
            if (!(ch == ' ' || ch == '\r' || ch == '\n' || ch == '\t')) {
                return;
            } else {
                byteBuf.readByte(); //move the read index
            }
        }
    }
}
