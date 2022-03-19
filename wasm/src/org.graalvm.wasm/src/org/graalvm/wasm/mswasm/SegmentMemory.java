package org.graalvm.wasm.mswasm;

import java.lang.reflect.Field;
import java.util.ArrayList;

import static org.graalvm.wasm.constants.Sizes.MAX_MEMORY_DECLARATION_SIZE;
import static org.graalvm.wasm.constants.Sizes.MAX_MEMORY_INSTANCE_SIZE;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.graalvm.wasm.collection.ByteArrayList;

import org.graalvm.wasm.exception.Failure;
import org.graalvm.wasm.exception.WasmException;
import org.graalvm.wasm.nodes.WasmNode;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.Node;

import org.graalvm.wasm.mswasm.*;
import org.graalvm.wasm.memory.WasmMemory;

public class SegmentMemory extends WasmMemory {
    public static final boolean DEBUG = false;
    public static final boolean DEBUG_FINE = false;

    /** Stores segments according to their integer keys */
    private SegmentList segments;

    public SegmentMemory() {
        // Provide dummy values for WasmMemory constructor
        super(0, MAX_MEMORY_DECLARATION_SIZE, 0, MAX_MEMORY_INSTANCE_SIZE);
        segments = new SegmentList();
    }

    // Methods to allocate and free memory

    /**
     * Allocate a segment with the given size in bytes. Returns a handle that accesses that
     * segment with offset 0.
     */
    public Handle allocSegment(int byteSize) {
        // Allocate segment with byte size
        Segment s = new Segment(byteSize);

        // Record segment and create handle
        segments.insert(s);
        if (DEBUG) {
            System.err.println("[allocSegment] Created segment " + s.key() + 
                               " of size " + byteSize);
            System.err.println("[allocSegment] segments: " + segments);
        }
        return new Handle(s.key());
    }

    /**
     * Free the segment associated with the given handle. Traps if the handle is corrupted or
     * the segment is already freed.
     */
    public void freeSegment(Node node, Handle h) {
        Segment seg = getAndValidateSegment(node, h);
        seg.free();
        if (DEBUG_FINE) {
            System.err.println("[freeSegment] Freed segment " + seg.key());
            System.err.println("[freeSegment] segments: " + segments);
        }
    }


    // Methods to validate attempts to use a handle

    /**
     * Retrieve the segment referenced by the given handle, validating
     * 
     *   (1) the handle is neither null nor corrupted;
     *   (2) the segment exists; and
     *   (3) the segment has not been freed.
     * 
     * Traps if any of these conditions are violated. Otherwise, returns the segment.
     */
    public Segment getAndValidateSegment(Node node, Handle h) {
        if (DEBUG_FINE) {
            System.err.println("[getAndValidateSegment] called on " + h);
        }
        if (h.isNull()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw trapNull(node, h);
        }

        int key = h.segment;
        if (!segments.contains(key)) {
            if (DEBUG) {
                System.err.println("[getAndValidateSegment] Couldn't find segment with key " + key);
                System.err.println("[getAndValidateSegment] segments: " + segments);
            }
            // If the segment does not exist, assume the handle is corrupted
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw trapCorrupted(node, h);
        }

        Segment segment = segments.get(key);
        if (segment.isFree()) {
            throw trapFreed(node, key);
        }

        return segment;
    }

    /**
     * Trap on an attempt to dereference a corrupted handle.
     * @param node
     * @return
     */
    @TruffleBoundary
    protected final WasmException trapCorrupted(Node node, Handle h) {
        final String message = String.format("Handle into segment %d with offset %d is corrupted",
                                             h.segment, h.offset);
        if (DEBUG) {
            System.err.println("[trapCorrupted] " + message + ". Printing stack trace...");
            new Exception().printStackTrace();
        }
        return WasmException.create(Failure.OUT_OF_BOUNDS_MEMORY_ACCESS, node, message);
    }

    /**
     * Trap on an attempt to dereference a null handle.
     * @param node
     * @return
     */
    @TruffleBoundary
    protected final WasmException trapNull(Node node, Handle h) {
        final String message = String.format("Handle into segment %d with offset %d is null",
                                             h.segment, h.offset);
        if (DEBUG) {
            System.err.println("[trapNull] " + message + ". Printing stack trace...");
            new Exception().printStackTrace();
        }
        return WasmException.create(Failure.OUT_OF_BOUNDS_MEMORY_ACCESS, node, message);
    }

    /**
     * Trap on an attempt to access a freed segment of memory.
     * @param node
     * @return
     */
    @TruffleBoundary
    protected final WasmException trapFreed(Node node, int segmentKey) {
        final String message = String.format("Segment with key %d has already been freed",
                                    segmentKey);
        if (DEBUG) {
            System.err.println("[trapCorrupted] " + message + ". Printing stack trace...");
            new Exception().printStackTrace();
        }
        return WasmException.create(Failure.OUT_OF_BOUNDS_MEMORY_ACCESS, node, message);
    }
    
    /**
     * Trap on an attempt to store to or load from memory out-of-bounds.
     */
    @TruffleBoundary
    protected final WasmException trapOutOfBounds(Node node, int key, int length) {
        final String message = String.format("%d-byte segment memory access to segment %d is out-of-bounds",
                                length, key);
        if (DEBUG) {
            System.err.println("[trapOutOfBounds] " + message + ". Printing stack trace...");
            new Exception().printStackTrace();
        }
        return WasmException.create(Failure.OUT_OF_BOUNDS_MEMORY_ACCESS, node, message);
    }


    // Methods to load from a segment

    @Override
    public int load_i32(Node node, long handle) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            return s.getInt(h.offset);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 4);
        }
    }
    
    @Override
    public long load_i64(Node node, long handle) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        long value = s.getLong(h.offset);
        try {
            return value;
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 8);
        }
    }
    
    @Override
    public float load_f32(Node node, long handle) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            return s.getFloat(h.offset);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 4);
        }
    }
    
    @Override
    public double load_f64(Node node, long handle) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            return s.getDouble(h.offset);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 8);
        }
    }
    
    @Override
    public int load_i32_8s(Node node, long handle) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            return s.getByte(h.offset);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 1);
        }
    }
    
    public int load_i32_8u(Node node, long handle) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            return 0x0000_00ff & s.getByte(h.offset);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 1);
        }
    }
    
    public int load_i32_16s(Node node, long handle) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            return s.getShort(h.offset);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 2);
        }
    }
    
    public int load_i32_16u(Node node, long handle) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            return 0x0000_ffff & s.getShort(h.offset);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 2);
        }
    }
    
    public long load_i64_8s(Node node, long handle) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            return s.getByte(h.offset);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 1);
        }
    }
    
    public long load_i64_8u(Node node, long handle) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            return 0x0000_0000_0000_00ffL & s.getByte(h.offset);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 1);
        }
    }
    
    public long load_i64_16s(Node node, long handle) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            return s.getShort(h.offset);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 2);
        }
    }

    public long load_i64_16u(Node node, long handle) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            return 0x0000_0000_0000_ffffL & s.getShort(h.offset);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 2);
        }
    }
    
    public long load_i64_32s(Node node, long handle) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            return s.getInt(h.offset);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 4);
        }
    }
    
    public long load_i64_32u(Node node, long handle) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            return 0x0000_0000_ffff_ffffL & s.getInt(h.offset);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 4);
        }
    }

    /**
     * Load a handle from memory as a 64-bit long.
     */
    public Handle load_handle(Node node, long handle) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            return Handle.longBitsToHandle(s.getLong(h.offset));
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 4);
        }
    }


    // Methods to store data to segments
    
    public void store_i32(Node node, long handle, int value) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            s.putInt(h.offset, value);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 4);
        }
    }

    public void store_i64(Node node, long handle, long value) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            s.putLong(h.offset, value);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 8);
        }
    }
    
    public void store_f32(Node node, long handle, float value) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            s.putFloat(h.offset, value);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 4);
        }
    }
    
    public void store_f64(Node node, long handle, double value) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            s.putDouble(h.offset, value);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 8);
        }
    }

    public void store_i32_8(Node node, long handle, byte value) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            s.putByte(h.offset, value);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 1);
        }
    }
    
    public void store_i32_16(Node node, long handle, short value) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            s.putShort(h.offset, value);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 2);
        }
    }
    
    public void store_i64_8(Node node, long handle, byte value) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            s.putByte(h.offset, value);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 1);
        }
    }
    
    public void store_i64_16(Node node, long handle, short value) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            s.putShort(h.offset, value);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 2);
        }
    }

    public void store_i64_32(Node node, long handle, int value) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);
        try {
            s.putInt(h.offset, value);
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 4);
        }
    }

    /**
     * Store a handle to memory as a 64-bit long.
     */
    public void store_handle(Node node, long handle, Handle value) {
        Handle h = Handle.longBitsToHandle(handle);
        Segment s = getAndValidateSegment(node, h);        
        try {
            s.putLong(h.offset, Handle.handleToRawLongBits(value));
        } catch (final IndexOutOfBoundsException e) {
            throw trapOutOfBounds(node, s.key(), 8);
        }
    }


    // Misc WasmMemory methods

    /** 
     * Copy should never be called with segment memory. Traps if invoked. 
     */
    @Override
    public void copy(Node node, int src, int dst, int n) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        final String message = "Segment memory does not support copying memory";
        throw WasmException.create(Failure.INVALID_MSWASM_OPERATION, message);
    }

    /** Returns number of segments in the segment memory. */
    @Override
    public int size() {
        return segments.size();
    }

    /** Placeholder. Returns max int. */
    @Override
    public long byteSize() {
        return Integer.MAX_VALUE;
    }

    /** 
     * Grow should never be called with segment memory. Traps if invoked.
     */
    @Override
    public boolean grow(int extraPageSize) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        final String message = "Segment memory does not support growing memory";
        throw WasmException.create(Failure.INVALID_MSWASM_OPERATION, message);
    }

    /**
     * Frees all segments and empties the segment hashmap. (Equivalent to close().)
     */
    @Override
    public void reset() {
        for (Segment s : segments.segments) {
            if (s != null && !s.isFree()) {
                s.free();
            }
        }
        segments.clear();
    }

    /**
     * Creates a "new" segment memory instance that references the same segment map as this
     * segment memory.
     */
    @Override
    public WasmMemory duplicate() {
        SegmentMemory memory = new SegmentMemory();
        memory.segments = this.segments;
        return memory;
    }

    /**
     * Frees all segments and empties the segment hashmap. (Equivalent to reset().)
     */
    @Override
    public void close() {
        reset();
    }

    /**
     * Segment memory cannot be converted to a byte buffer. Traps if invoked.
     */
    @Override
    public ByteBuffer asByteBuffer() {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        final String message = "Segment memory cannot be converted to a byte buffer";
        throw WasmException.create(Failure.INVALID_MSWASM_OPERATION, message);
    }


    // String reading/writing methods
    
    /**
     * Reads the null-terminated UTF-8 string starting at {@code startOffset}.
     *
     * @param startOffset memory index of the first character
     * @param node a node indicating the location where this read occurred in the Truffle AST. It
     *            may be {@code null} to indicate that the location is not available.
     * @return the read {@code String}
     */
    @CompilerDirectives.TruffleBoundary
    public String readString(long startOffset, WasmNode node) {
        ByteArrayList bytes = new ByteArrayList();
        byte currentByte;
        long offset = startOffset;

        while ((currentByte = (byte) load_i32_8u(node, offset)) != 0) {
            bytes.add(currentByte);
            ++offset;
        }

        return new String(bytes.toArray(), StandardCharsets.UTF_8);
    }

    /**
     * Reads the UTF-8 string of length {@code length} starting at {@code startOffset}.
     *
     * @param startOffset memory index of the first character
     * @param length length of the UTF-8 string to read in bytes
     * @param node a node indicating the location where this read occurred in the Truffle AST. It
     *            may be {@code null} to indicate that the location is not available.
     * @return the read {@code String}
     */
    @CompilerDirectives.TruffleBoundary
    public final String readString(long startOffset, int length, Node node) {
        ByteArrayList bytes = new ByteArrayList();

        for (int i = 0; i < length; ++i) {
            bytes.add((byte) load_i32_8u(node, startOffset + i));
        }

        return new String(bytes.toArray(), StandardCharsets.UTF_8);
    }

    /**
     * Writes a Java String at offset {@code offset}.
     * <p>
     * The written string is encoded as UTF-8 and <em>not</em> terminated with a null character.
     *
     * @param node a node indicating the location where this write occurred in the Truffle AST. It
     *            may be {@code null} to indicate that the location is not available.
     * @param string the string to write
     * @param offset memory index where to write the string
     * @param length the maximum number of bytes to write, including the trailing null character
     * @return the number of bytes written, including the trailing null character
     */
    @CompilerDirectives.TruffleBoundary
    public final int writeString(Node node, String string, long offset, int length) {
        final byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        int i = 0;
        for (; i < bytes.length && i < length; ++i) {
            store_i32_8(node, offset + i, bytes[i]);
        }
        return i;
    }

    public final int writeString(Node node, String string, long offset) {
        return writeString(node, string, offset, Integer.MAX_VALUE);
    }

}
