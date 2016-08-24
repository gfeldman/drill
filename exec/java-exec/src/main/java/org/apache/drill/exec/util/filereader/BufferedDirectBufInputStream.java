/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.util.filereader;

import com.google.common.base.Preconditions;
import io.netty.buffer.DrillBuf;
import org.apache.drill.common.config.DrillConfig;
import org.apache.drill.exec.memory.BufferAllocator;
import org.apache.drill.exec.memory.RootAllocatorFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.hadoop.Footer;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.util.CompatibilityUtil;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * <code>BufferedDirectBufInputStream</code>  reads from the
 * underlying <code>InputStream</code> in blocks of data, into an
 * internal buffer. The internal buffer is a direct memory backed
 * buffer. The implementation is similar to the <code>BufferedInputStream</code>
 * class except that the internal buffer is a Drillbuf and
 * not a byte array. The mark and reset methods of the underlying
 * <code>InputStream</code>are not supported.
 */
public class BufferedDirectBufInputStream extends DirectBufInputStream implements Closeable {

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(BufferedDirectBufInputStream.class);

  private static int defaultBufferSize = 8192 * 1024; // 8 MiB
  private static int defaultTempBufferSize = 8192; // 8 KiB

  /**
   * The internal buffer to keep data read from the underlying inputStream.
   * <code>internalBuffer[0]</code>  through <code>internalBuffer[count-1] </code>
   * contains data read from the underlying  input stream.
   */
  protected volatile DrillBuf internalBuffer; // the internal buffer

  /**
   * The number of valid bytes in <code>internalBuffer</code>.
   * <code> count </code> is always in the range <code>[0,internalBuffer.capacity]</code>
   * <code>internalBuffer[count-1]</code> is the last valid byte in the buffer.
   */
  protected int count;

  /**
   * The current read position in the buffer; the index of the next
   * character to be read from the <code>internalBuffer</code> array.
   * <p/>
   * This value is always in the range <code>[0,count]</code>.
   * If <code>curPosInBuffer</code> is equal to <code>count></code> then we have read
   * all the buffered data and the next read (or skip) will require more data to be read
   * from the underlying input stream.
   */
  protected int curPosInBuffer;

  protected long curPosInStream; // current offset in the input stream

  private final int bufSize;

  private volatile DrillBuf tempBuffer; // a temp Buffer for use by read(byte[] buf, int off, int len)


  private DrillBuf getBuf() throws IOException {
    checkInputStreamState();
    if (internalBuffer == null) {
      throw new IOException("Input stream is closed.");
    }
    return this.internalBuffer;
  }

  /**
   * Creates a <code>BufferedDirectBufInputStream</code>
   * with the default (8 MiB) buffer size.
   */
  public BufferedDirectBufInputStream(InputStream in, BufferAllocator allocator, String id,
      long startOffset, long totalByteSize, boolean enableHints) {
    this(in, allocator, id, startOffset, totalByteSize, defaultBufferSize, enableHints);
  }

  /**
   * Creates a <code>BufferedDirectBufInputStream</code>
   * with the specified buffer size.
   */
  public BufferedDirectBufInputStream(InputStream in, BufferAllocator allocator, String id,
      long startOffset, long totalByteSize, int bufSize, boolean enableHints) {
    super(in, allocator, id, startOffset, totalByteSize, enableHints);
    Preconditions.checkArgument(bufSize >= 0);
    // We make the buffer size the smaller of the buffer Size parameter or the total Byte Size
    // rounded to next highest pwoer of two
    int bSize = bufSize < (int) totalByteSize ? bufSize : (int) totalByteSize;
    // round up to next power of 2
    bSize--;
    bSize |= bSize >>> 1;
    bSize |= bSize >>> 2;
    bSize |= bSize >>> 4;
    bSize |= bSize >>> 8;
    bSize |= bSize >>> 16;
    bSize++;
    this.bufSize = bSize;

  }

  @Override public void init() throws UnsupportedOperationException, IOException {
    super.init();
    this.internalBuffer = this.allocator.buffer(this.bufSize);
    this.tempBuffer = this.allocator.buffer(defaultTempBufferSize);
    int bytesRead = getNextBlock();
    if (bytesRead <= 0) {
      throw new IOException("End of stream reached while initializing buffered reader.");
    }
  }

  /**
   * Read one more block from the underlying stream.
   * Assumes we have reached the end of buffered data
   * Assumes it is being called from a synchronized block.
   * returns number of bytes read or -1 if EOF
   */
  private int getNextBlock() throws IOException {
    Preconditions.checkState(this.curPosInBuffer >= this.count,
        "Internal error: Buffered stream has not been consumed and trying to read more from underlying stream");
    checkInputStreamState();
    DrillBuf buffer = getBuf();
    buffer.clear();
    this.count = this.curPosInBuffer = 0;

    // We *cannot* rely on the totalByteSize being correct because
    // metadata for Parquet files is incorrect. So we read as
    // much as we can up to the size of the buffer
    //int bytesToRead = buffer.capacity() <= (totalByteSize + startOffset - curPosInStream ) ?
    //    buffer.Capacity() :
    //    (int) (totalByteSize + startOffset - curPosInStream );
    int bytesToRead = buffer.capacity();

    ByteBuffer directBuffer = buffer.nioBuffer(curPosInBuffer, bytesToRead);
    // The DFS can return *more* bytes than requested if the capacity of the buffer is greater.
    // i.e 'n' can be greater than bytes requested which is pretty stupid and violates
    // the API contract; but we still have to deal with it. So we make sure the size of the
    // buffer is exactly the same as the number of bytes requested
    int bytesRead = -1;
    int nBytes = 0;
    if (bytesToRead > 0) {
      try {
        nBytes = CompatibilityUtil.getBuf(getInputStream(), directBuffer, bytesToRead);
      } catch (Exception e) {
        logger.error("Error reading from stream {}. Error was : {}", this.streamId, e.getMessage());
      }
      if (nBytes > 0) {
        buffer.writerIndex(nBytes);
        this.count = nBytes + this.curPosInBuffer;
        this.curPosInStream = getInputStream().getPos();
        bytesRead = nBytes;
        logger.trace(
            "Stream: {}, StartOffset: {}, TotalByteSize: {}, BufferSize: {}, BytesRead: {}, Count: {}, "
                + "CurPosInStream: {}, CurPosInBuffer: {}", this.streamId, this.startOffset,
            this.totalByteSize, this.bufSize, bytesRead, this.count, this.curPosInStream,
            this.curPosInBuffer);
      }
    }
    return this.count - this.curPosInBuffer;
  }

  // Reads from the internal Buffer into the output buffer
  // May read less than the requested size if the remaining data in the buffer
  // is less than the requested amount
  private int readInternal(DrillBuf buf, int off, int len) throws IOException {
    // check how many bytes are available in the buffer.
    int bytesAvailable = this.count - this.curPosInBuffer;
    if (bytesAvailable <= 0) {
      // read more
      int bytesRead = getNextBlock();
      if (bytesRead <= 0) { // End of stream
        return -1;
      }
    }
    bytesAvailable = this.count - this.curPosInBuffer;
    //copy into output buffer
    int copyBytes = bytesAvailable < len ? bytesAvailable : len;
    getBuf().getBytes(curPosInBuffer, buf, off, copyBytes);
    buf.writerIndex(off + copyBytes);
    this.curPosInBuffer += copyBytes;

    return copyBytes;
  }

  // Reads from the internal Buffer into the output buffer
  // May read less than the requested size if the remaining data in the buffer
  // is less than the requested amount
  // Does not make a copy but returns a slice of the internal buffer.
  // Returns null if end of stream is reached
  private DrillBuf readInternal(int off, int len) throws IOException {
    // check how many bytes are available in the buffer.
    int bytesAvailable = this.count - this.curPosInBuffer;
    if (bytesAvailable <= 0) {
      // read more
      int bytesRead = getNextBlock();
      if (bytesRead <= 0) { // End of stream
        return null;
      }
    }
    bytesAvailable = this.count - this.curPosInBuffer;
    // return a slice as the  output
    int bytesToRead = bytesAvailable < len ? bytesAvailable : len;
    DrillBuf newBuf = this.getBuf().slice(off, bytesToRead);
    newBuf.retain();
    return newBuf;
  }

  /**
   * Implements the  <code>read</code> method of <code>InputStream</code>.
   * returns one more byte or -1 if end of stream is reached.
   */
  public synchronized int read() throws IOException {
    if (this.count - this.curPosInBuffer <= 0) {
      int bytesRead = getNextBlock();
      // reached end of stream
      if (bytesRead <= 0) {
        return -1;
      }
    }
    this.curPosInBuffer++;
    return getBuf().nioBuffer().get() & 0xff;
  }

  /**
   * Has the same contract as {@link java.io.InputStream#read(byte[], int, int)}
   * Except with DrillBuf
   */
  public synchronized int read(DrillBuf buf, int off, int len) throws IOException {
    checkInputStreamState();
    Preconditions.checkArgument((off >= 0) && (len >= 0) && (buf.capacity()) >= (off + len));
    int bytesRead = 0;
    do {
      int readStart = off + bytesRead;
      int lenToRead = len - bytesRead;
      int nRead = readInternal(buf, readStart, lenToRead);
      if (nRead <= 0) {// if End of stream
        if (bytesRead == 0) { // no bytes read at all
          return -1;
        } else {
          return bytesRead;
        }
      } else {
        bytesRead += nRead;
        // If the last read caused us to reach the end of stream
        // we are done
        InputStream input = in;
        if (input != null && input.available() <= 0) {
          return bytesRead;
        }
      }
    } while (bytesRead < len);
    return bytesRead;
  }


  @Override public int read(byte[] b) throws IOException {
    return b.length == 1 ? read() : read(b, (int) 0, b.length);
  }


  @Override public int read(byte[] buf, int off, int len) throws IOException {
    checkInputStreamState();
    Preconditions.checkArgument((off >= 0) && (len >= 0) && (buf.length) >= (off + len));
    int bytesRead = 0;
    if (len == 0) {
      return 0;
    }
    DrillBuf byteBuf;
    if (len <= defaultTempBufferSize) {
      byteBuf = tempBuffer;
    } else {
      byteBuf = this.allocator.buffer(len);
    }
    do {
      int readStart = off + bytesRead;
      int lenToRead = len - bytesRead;
      int nRead = readInternal(byteBuf, readStart, lenToRead);
      if (nRead <= 0) {// if End of stream
        if (bytesRead == 0) { // no bytes read at all
          return -1;
        } else {
          return bytesRead;
        }
      } else {
        byteBuf.nioBuffer().get(buf, off + bytesRead, len - bytesRead);
        byteBuf.clear();
        bytesRead += nRead;
      }
    } while (bytesRead < len);

    if (len > defaultTempBufferSize) {
      byteBuf.release();
    }

    return bytesRead;
  }


  /**
   * Has the same contract as {@link java.io.InputStream#skip(long)}
   * Skips upto the next n bytes.
   * Skip may return with less than n bytes skipped
   */
  @Override public synchronized long skip(long n) throws IOException {
    checkInputStreamState();
    long bytesAvailable = this.count - this.curPosInBuffer;
    long bytesSkipped = 0;
    if (n <= 0) {
      return 0;
    }
    if (bytesAvailable <= 0) {
      checkInputStreamState();
      bytesAvailable = getNextBlock();
      if (bytesAvailable <= 0) { // End of stream
        return 0;
      }
    }
    bytesSkipped = bytesAvailable < n ? bytesAvailable : n;
    this.curPosInBuffer += bytesSkipped;

    return bytesSkipped;
  }


  @Override public synchronized int available() throws IOException {
    checkInputStreamState();
    int bytesAvailable = this.count - this.curPosInBuffer;
    int underlyingAvailable = getInputStream().available();
    int available = bytesAvailable + underlyingAvailable;
    if (available < 0) { // overflow
      return Integer.MAX_VALUE;
    }
    return available;
  }

  @Override public synchronized void mark(int readlimit) {
    throw new UnsupportedOperationException("Mark/reset is not supported.");
  }

  @Override public synchronized void reset() throws IOException {
    throw new UnsupportedOperationException("Mark/reset is not supported.");
  }

  @Override public boolean markSupported() {
    return false;
  }

  /*
    Returns the current position from the beginning of the underlying input stream
   */
  public long getPos() throws IOException {
    return curPosInBuffer + startOffset;
  }

  public boolean hasRemainder() throws IOException {
    return available() > 0;
  }

  public void close() throws IOException {
    DrillBuf buffer;
    InputStream inp;
    if ((inp = in) != null) {
      in = null;
      inp.close();
    }
    if ((buffer = this.internalBuffer) != null) {
      synchronized (this) {
        this.internalBuffer = null;
        buffer.release();
      }
    }
    if ((buffer = this.tempBuffer) != null) {
      synchronized (this) {
        this.tempBuffer = null;
        buffer.release();
      }
    }
  }

  /**
   * Uncomment For testing Parquet files that are too big to use in unit tests
   * @param args
   */
  /*
  public static void main(String[] args) {
    final DrillConfig config = DrillConfig.create();
    final BufferAllocator allocator = RootAllocatorFactory.newRoot(config);
    final Configuration dfsConfig = new Configuration();
    String fileName = args[0];
    Path filePath = new Path(fileName);
    final int BUFSZ = 8 * 1024 * 1024;
    try {
      List<Footer> footers = ParquetFileReader.readFooters(dfsConfig, filePath);
      Footer footer = (Footer) footers.iterator().next();
      FileSystem fs = FileSystem.get(dfsConfig);
      int rowGroupIndex = 0;
      List<BlockMetaData> blocks = footer.getParquetMetadata().getBlocks();
      for (BlockMetaData block : blocks) {
        List<ColumnChunkMetaData> columns = block.getColumns();
        for (ColumnChunkMetaData columnMetadata : columns) {
          FSDataInputStream inputStream = fs.open(filePath);
          long startOffset = columnMetadata.getStartingPos();
          long totalByteSize = columnMetadata.getTotalSize();
          String streamId = fileName + ":" + columnMetadata.toString();
          BufferedDirectBufInputStream reader =
              new BufferedDirectBufInputStream(inputStream, allocator, streamId, startOffset, totalByteSize,
                  BUFSZ, true);
          reader.init();
          while (true) {
            try {
              DrillBuf buf = reader.getNext(BUFSZ - 1);
              if (buf == null) {
                break;
              }
              buf.release();
            } catch (Exception e) {
              e.printStackTrace();
              break;
            }
          }
          reader.close();
        }
      } // for each Block
    } catch (Exception e) {
      e.printStackTrace();
    }
    allocator.close();
    return;
  }
  */
}