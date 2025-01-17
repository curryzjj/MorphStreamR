package common.io.Encoding.decoder;

import common.io.Enums.Encoding;
import common.io.Utils.ReadWriteIOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class GorillaDecoderV1 extends Decoder {

    protected static final int EOF = -1;
    private static final Logger logger = LoggerFactory.getLogger(GorillaDecoderV1.class);
    // flag to indicate whether the first value is read from stream
    protected boolean flag;
    protected int leadingZeroNum;
    protected int tailingZeroNum;
    protected boolean isEnd;
    // 8-bit buffer of bits to write out
    protected int buffer;
    // number of bits remaining in buffer
    protected int numberLeftInBuffer;

    protected boolean nextFlag1;
    protected boolean nextFlag2;

    protected GorillaDecoderV1() {
        super(Encoding.GORILLA_V1);
        reset();
    }

    @Override
    public void reset() {
        this.flag = false;
        this.isEnd = false;
        this.numberLeftInBuffer = 0;
    }

    @Override
    public boolean hasNext(ByteBuffer buffer) throws IOException {
        return buffer.remaining() > 0 || !isEnd;
    }

    protected boolean isEmpty() {
        return buffer == EOF;
    }

    protected boolean readBit(ByteBuffer buffer) throws IOException {
        if (numberLeftInBuffer == 0 && !isEnd) {
            fillBuffer(buffer);
        }
        if (isEmpty()) {
            throw new IOException("Reading from empty buffer");
        }
        numberLeftInBuffer--;
        return ((this.buffer >> numberLeftInBuffer) & 1) == 1;
    }

    /**
     * read one byte and save it in the buffer.
     *
     * @param buffer ByteBuffer to read
     */
    protected void fillBuffer(ByteBuffer buffer) {
        if (buffer.remaining() >= 1) {
            this.buffer = ReadWriteIOUtils.read(buffer);
            numberLeftInBuffer = 8;
        } else {
            logger.error("Failed to fill a new buffer, because there is no byte to read");
            this.buffer = EOF;
            numberLeftInBuffer = -1;
        }
    }

    /**
     * read some bits and convert them to an int value.
     *
     * @param buffer stream to read
     * @param len number of bit to read
     * @return converted int value
     * @throws IOException cannot read from stream
     */
    protected int readIntFromStream(ByteBuffer buffer, int len) throws IOException {
        int num = 0;
        for (int i = 0; i < len; i++) {
            int bit = readBit(buffer) ? 1 : 0;
            num |= bit << (len - 1 - i);
        }
        return num;
    }

    /**
     * read some bits and convert them to a long value.
     *
     * @param buffer stream to read
     * @param len number of bit to read
     * @return converted long value
     * @throws IOException cannot read from stream
     */
    protected long readLongFromStream(ByteBuffer buffer, int len) throws IOException {
        long num = 0;
        for (int i = 0; i < len; i++) {
            long bit = readBit(buffer) ? 1 : 0;
            num |= bit << (len - 1 - i);
        }
        return num;
    }
}
