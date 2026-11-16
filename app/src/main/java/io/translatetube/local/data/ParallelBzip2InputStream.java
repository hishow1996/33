package io.translatetube.local.data;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.io.RandomAccessFile;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多线程解 bzip2（单个 .bz2 流）。
 *
 * bzip2 由许多互相独立的数据块组成（每块最多 900 KB 解压数据），每块以 48 位魔数
 * 0x314159265359 开头，但块和块之间不按字节对齐。这里先在压缩文件里找出每块的起点，
 * 再把每一块的比特原样搬到一个临时的单块 bzip2 流里，交给 commons-compress 解码
 * （块 CRC、合并 CRC 仍由它校验），多个块同时解，最后按原顺序输出。
 *
 * 任何不符合预期的情况（不是单个标准流、找不到结尾、块解码失败、整体 CRC 对不上）
 * 都抛 {@link ParallelBzip2Exception}，调用方应当退回普通的单线程解压。
 * 底层读文件失败等普通 I/O 错误仍然是 IOException。
 */
public final class ParallelBzip2InputStream extends InputStream {

    /** 并行解压本身出了问题（不是文件读写问题）；调用方应退回单线程解压。 */
    public static final class ParallelBzip2Exception extends IOException {
        public ParallelBzip2Exception(String message) {
            super(message);
        }

        public ParallelBzip2Exception(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final long BLOCK_MAGIC = 0x314159265359L;
    private static final long END_MAGIC = 0x177245385090L;
    private static final long MASK_48 = 0xFFFFFFFFFFFFL;
    private static final int SCAN_BUFFER_BYTES = 1 << 20;

    /** 一块的结果：解压后的字节，和它在压缩文件里结束的位置（字节）。 */
    private static final class Decoded {
        final byte[] data;
        final long blockCrc;
        final long compressedEndByte;

        Decoded(byte[] data, long blockCrc, long compressedEndByte) {
            this.data = data;
            this.blockCrc = blockCrc;
            this.compressedEndByte = compressedEndByte;
        }
    }

    /** 队列里的占位：要么是一个待完成的块，要么是流结束的标记。 */
    private static final class Item {
        final Future<Decoded> future;
        final long storedCombinedCrc;
        final boolean end;

        Item(Future<Decoded> future) {
            this.future = future;
            this.storedCombinedCrc = 0L;
            this.end = false;
        }

        Item(long storedCombinedCrc) {
            this.future = null;
            this.storedCombinedCrc = storedCombinedCrc;
            this.end = true;
        }
    }

    private static final AtomicInteger POOL_ID = new AtomicInteger();

    private final FileChannel channel;
    private final long fileLength;
    private final int levelByte;
    private final ExecutorService decoders;
    private final Thread scanner;
    private final BlockingQueue<Item> queue;
    private final AtomicLong compressedBytesConsumed = new AtomicLong();
    private volatile Throwable scanFailure;
    private volatile boolean closed;

    private byte[] current = new byte[0];
    private int currentPos;
    private boolean finished;
    private int combinedCrc;

    /**
     * @param file    .bz2 文件
     * @param threads 同时解码的线程数（至少 1）
     */
    public ParallelBzip2InputStream(File file, int threads) throws IOException {
        int workers = Math.max(1, threads);
        this.channel = new RandomAccessFile(file, "r").getChannel();
        boolean ok = false;
        try {
            this.fileLength = channel.size();
            ByteBuffer header = ByteBuffer.allocate(4);
            readFully(header, 0L);
            int b = header.get(0) & 0xff;
            int z = header.get(1) & 0xff;
            int h = header.get(2) & 0xff;
            int level = header.get(3) & 0xff;
            if (b != 'B' || z != 'Z' || h != 'h' || level < '1' || level > '9') {
                throw new ParallelBzip2Exception("不是标准 bzip2 文件头");
            }
            this.levelByte = level;
            this.queue = new ArrayBlockingQueue<>(workers * 2 + 1);
            final int poolId = POOL_ID.incrementAndGet();
            final AtomicInteger threadId = new AtomicInteger();
            ThreadFactory factory = runnable -> {
                Thread t = new Thread(runnable, "bz2-decode-" + poolId + "-" + threadId.incrementAndGet());
                t.setDaemon(true);
                return t;
            };
            this.decoders = Executors.newFixedThreadPool(workers, factory);
            this.scanner = new Thread(this::scanLoop, "bz2-scan-" + poolId);
            this.scanner.setDaemon(true);
            this.scanner.start();
            ok = true;
        } finally {
            if (!ok) channel.close();
        }
    }

    /** 已经交给读取方的那部分压缩数据的字节数，用于显示进度。 */
    public long compressedBytesConsumed() {
        return Math.min(compressedBytesConsumed.get(), fileLength);
    }

    // ---------------------------------------------------------------- scanning

    private void scanLoop() {
        try {
            scanBlocks();
        } catch (Throwable t) {
            if (!closed) {
                scanFailure = t;
                // 唤醒可能正在等队列的读取方（队列满时等它腾出位置）。
                try {
                    putBlocking(new Item(0L));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void scanBlocks() throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(SCAN_BUFFER_BYTES);
        long position = 4L; // 文件头之后
        long window = 0L;
        long bytesSeen = 0L; // 已并入 window 的字节数（从文件头之后算起）
        long blockStartBit = -1L;

        while (position < fileLength && !closed) {
            buffer.clear();
            int read = channel.read(buffer, position);
            if (read <= 0) break;
            byte[] raw = buffer.array();
            for (int i = 0; i < read; i++) {
                window = (window << 8) | (raw[i] & 0xffL);
                bytesSeen++;
                if (bytesSeen < 6) continue;
                long endBit = (position + i + 1L) * 8L;
                for (int k = 0; k < 8; k++) {
                    long candidate = (window >>> k) & MASK_48;
                    if (candidate != BLOCK_MAGIC && candidate != END_MAGIC) continue;
                    long magicStartBit = endBit - k - 48L;
                    if (magicStartBit < 32L) continue;
                    if (blockStartBit >= 0L) {
                        submitBlock(blockStartBit, magicStartBit);
                    } else if (magicStartBit != 32L) {
                        throw new ParallelBzip2Exception("第一块没有紧跟在文件头后面");
                    }
                    if (candidate == END_MAGIC) {
                        finishStream(magicStartBit);
                        return;
                    }
                    blockStartBit = magicStartBit;
                    break;
                }
            }
            position += read;
        }
        if (!closed) throw new ParallelBzip2Exception("没有找到 bzip2 流结束标记");
    }

    private void finishStream(long endMagicStartBit) throws IOException, InterruptedException {
        // 结束魔数之后是 32 位合并 CRC，再补零到字节边界；之后不能再有数据（不接受多流拼接）。
        long crcStartBit = endMagicStartBit + 48L;
        long crcEndBit = crcStartBit + 32L;
        long totalBytes = (crcEndBit + 7L) / 8L;
        if (totalBytes > fileLength) throw new ParallelBzip2Exception("结束标记被截断");
        if (totalBytes != fileLength) throw new ParallelBzip2Exception("bzip2 流之后还有其他数据（可能是多流拼接）");
        long stored = readBits(crcStartBit, 32);
        putBlocking(new Item(stored));
    }

    private void submitBlock(final long startBit, final long endBit) throws InterruptedException {
        Future<Decoded> future = decoders.submit(() -> decodeBlock(startBit, endBit));
        putBlocking(new Item(future));
    }

    private void putBlocking(Item item) throws InterruptedException {
        while (!closed) {
            if (queue.offer(item, 200L, java.util.concurrent.TimeUnit.MILLISECONDS)) return;
        }
    }

    // ---------------------------------------------------------------- decoding

    private Decoded decodeBlock(long startBit, long endBit) throws IOException {
        if (endBit <= startBit + 80L) throw new ParallelBzip2Exception("块长度异常");
        long firstByte = startBit >>> 3;
        long lastByte = (endBit + 7L) >>> 3; // 开区间
        long span = lastByte - firstByte + 1L;
        if (span > (long) Integer.MAX_VALUE / 2L) throw new ParallelBzip2Exception("块太大");
        ByteBuffer raw = ByteBuffer.allocate((int) Math.min(span, lastByte - firstByte + 1L));
        long readTo = Math.min(fileLength, lastByte + 1L);
        raw.limit((int) (readTo - firstByte));
        readFully(raw, firstByte);
        byte[] src = raw.array();
        int shift = (int) (startBit & 7L);
        long blockBits = endBit - startBit;

        long blockCrc = readBitsFrom(src, shift + 48, 32);

        // 合成流：BZh<level> + 这一块的全部比特 + 结束魔数 + 合并 CRC（单块时等于块 CRC）。
        BitSink sink = new BitSink((int) ((blockBits + 48 + 32) / 8 + 16));
        sink.writeBytes(new byte[]{'B', 'Z', 'h', (byte) levelByte});
        sink.copyBits(src, shift, blockBits);
        sink.writeBits(END_MAGIC, 48);
        sink.writeBits(blockCrc, 32);
        byte[] synthetic = sink.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream((levelByte - '0') * 100_000 + 1024);
        try (BZip2CompressorInputStream in = new BZip2CompressorInputStream(new ByteArrayInputStream(synthetic))) {
            byte[] chunk = new byte[64 * 1024];
            int c;
            while ((c = in.read(chunk)) > 0) out.write(chunk, 0, c);
        } catch (IOException e) {
            throw new ParallelBzip2Exception("块解码失败", e);
        }
        return new Decoded(out.toByteArray(), blockCrc, (endBit + 7L) >>> 3);
    }

    // ---------------------------------------------------------------- file helpers

    private void readFully(ByteBuffer target, long position) throws IOException {
        long pos = position;
        while (target.hasRemaining()) {
            int c = channel.read(target, pos);
            if (c < 0) throw new ParallelBzip2Exception("压缩文件被截断");
            pos += c;
        }
    }

    private long readBits(long bitOffset, int bits) throws IOException {
        long firstByte = bitOffset >>> 3;
        int need = (int) (((bitOffset & 7L) + bits + 7L) / 8L);
        ByteBuffer b = ByteBuffer.allocate(need);
        readFully(b, firstByte);
        return readBitsFrom(b.array(), (int) (bitOffset & 7L), bits);
    }

    private static long readBitsFrom(byte[] data, int bitOffset, int bits) {
        long value = 0L;
        for (int i = 0; i < bits; i++) {
            int pos = bitOffset + i;
            int bit = (data[pos >>> 3] >>> (7 - (pos & 7))) & 1;
            value = (value << 1) | bit;
        }
        return value;
    }

    /** 按位写入的缓冲区（高位在前，和 bzip2 一致）。 */
    private static final class BitSink {
        private byte[] data;
        private long bitLength;

        BitSink(int expectedBytes) {
            data = new byte[Math.max(16, expectedBytes)];
        }

        private void ensure(long extraBits) {
            long needBytes = (bitLength + extraBits + 7L) / 8L;
            if (needBytes > data.length) {
                data = java.util.Arrays.copyOf(data, (int) Math.max(needBytes, data.length * 2L));
            }
        }

        void writeBytes(byte[] bytes) {
            for (byte b : bytes) writeBits(b & 0xffL, 8);
        }

        void writeBits(long value, int bits) {
            ensure(bits);
            for (int i = bits - 1; i >= 0; i--) {
                int bit = (int) ((value >>> i) & 1L);
                if (bit != 0) data[(int) (bitLength >>> 3)] |= (byte) (0x80 >>> (int) (bitLength & 7L));
                bitLength++;
            }
        }

        /** 从 src 的第 srcBitOffset 位起复制 count 位。 */
        void copyBits(byte[] src, int srcBitOffset, long count) {
            ensure(count);
            long remaining = count;
            int srcPos = srcBitOffset;
            // 先补齐到字节边界。
            while (remaining > 0 && (bitLength & 7L) != 0L) {
                writeBits((src[srcPos >>> 3] >>> (7 - (srcPos & 7))) & 1, 1);
                srcPos++;
                remaining--;
            }
            // 目标已对齐：整字节搬运（源可能未对齐）。
            int srcShift = srcPos & 7;
            int srcByte = srcPos >>> 3;
            int dstByte = (int) (bitLength >>> 3);
            while (remaining >= 8) {
                int v = srcShift == 0
                        ? src[srcByte] & 0xff
                        : (((src[srcByte] & 0xff) << srcShift) | ((src[srcByte + 1] & 0xff) >>> (8 - srcShift))) & 0xff;
                data[dstByte++] = (byte) v;
                srcByte++;
                bitLength += 8;
                remaining -= 8;
            }
            srcPos = (srcByte << 3) + srcShift;
            while (remaining > 0) {
                writeBits((src[srcPos >>> 3] >>> (7 - (srcPos & 7))) & 1, 1);
                srcPos++;
                remaining--;
            }
        }

        byte[] toByteArray() {
            return java.util.Arrays.copyOf(data, (int) ((bitLength + 7L) / 8L));
        }
    }

    // ---------------------------------------------------------------- InputStream

    private boolean advance() throws IOException {
        if (finished) return false;
        Item item;
        try {
            item = queue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("bzip2 解压被中断");
        }
        Throwable failure = scanFailure;
        if (failure != null) throw wrap(failure);
        if (item.end) {
            finished = true;
            if ((int) item.storedCombinedCrc != combinedCrc) {
                throw new ParallelBzip2Exception("bzip2 合并校验和不一致");
            }
            compressedBytesConsumed.set(fileLength);
            return false;
        }
        Decoded decoded;
        try {
            decoded = item.future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("bzip2 解压被中断");
        } catch (java.util.concurrent.ExecutionException e) {
            throw wrap(e.getCause() != null ? e.getCause() : e);
        }
        combinedCrc = ((combinedCrc << 1) | (combinedCrc >>> 31)) ^ (int) decoded.blockCrc;
        current = decoded.data;
        currentPos = 0;
        compressedBytesConsumed.set(decoded.compressedEndByte);
        return true;
    }

    private static IOException wrap(Throwable t) {
        if (t instanceof ParallelBzip2Exception) return (ParallelBzip2Exception) t;
        if (t instanceof IOException) return new ParallelBzip2Exception("并行解压读取失败", t);
        return new ParallelBzip2Exception("并行解压失败", t);
    }

    @Override
    public int read() throws IOException {
        while (currentPos >= current.length) {
            if (!advance()) return -1;
        }
        return current[currentPos++] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        while (currentPos >= current.length) {
            if (!advance()) return -1;
        }
        int count = Math.min(len, current.length - currentPos);
        System.arraycopy(current, currentPos, b, off, count);
        currentPos += count;
        return count;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        scanner.interrupt();
        decoders.shutdownNow();
        queue.clear();
        channel.close();
    }
}
