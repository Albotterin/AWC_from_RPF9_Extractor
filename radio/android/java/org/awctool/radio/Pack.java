package org.awctool.radio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A station pack (.radiopak) made by AWC Radio: one station, its logo and all its songs in one file.
 *
 * Layout (all numbers little endian):
 *   0   8 bytes  magic "AWCPACK1"
 *   8   u32      format version (1)
 *   12  u32      reserved
 *   16  u64      offset of the index
 *   24  u64      length of the index
 *   32  ...      the files (Ogg Opus songs, PNG logos), back to back
 *   idx          UTF-8 JSON: {"v":1,"created":"...","sig":"...","stations":[{name,logo,kind,songs:[{k,t,a,s}],
 *                jingles:[{k,s}]}],"files":{"t/<key>":[offset,length],"logos/<name>.png":[offset,length]}}
 *
 * The songs are played straight out of this file (MediaPlayer with file descriptor, offset and length), nothing is
 * copied or unpacked on the phone.
 */
final class Pack {
    static final class Entry {
        final long offset, length;

        Entry(long offset, long length) {
            this.offset = offset;
            this.length = length;
        }
    }

    private static final byte[] MAGIC = "AWCPACK1".getBytes(StandardCharsets.US_ASCII);
    static final int HEADER = 32;

    private final FileChannel channel;
    private final FileDescriptor fd;
    private final Closeable owner;
    private final Map<String, Entry> files = new HashMap<>();
    private final List<Library.Station> stations = new ArrayList<>();
    final String created;

    /** @param owner closed together with the pack (the ParcelFileDescriptor on Android), may be null */
    Pack(FileChannel channel, FileDescriptor fd, Closeable owner) throws IOException {
        this.channel = channel;
        this.fd = fd;
        this.owner = owner;
        try {
            ByteBuffer hdr = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
            readFully(hdr, 0);
            for (int i = 0; i < MAGIC.length; i++) {
                if (hdr.get(i) != MAGIC[i]) throw new IOException("not an AWC station pack");
            }
            int version = hdr.getInt(8);
            if (version != 1) throw new IOException("unsupported pack version " + version);
            long idxOff = hdr.getLong(16);
            long idxLen = hdr.getLong(24);
            long size = channel.size();
            if (idxOff < HEADER || idxLen <= 0 || idxLen > (64L << 20) || idxOff + idxLen > size) {
                throw new IOException("the pack file is incomplete (copy it again)");
            }
            ByteBuffer idx = ByteBuffer.allocate((int) idxLen);
            readFully(idx, idxOff);
            JSONObject root = new JSONObject(new String(idx.array(), StandardCharsets.UTF_8));
            created = root.optString("created", "");
            JSONObject f = root.getJSONObject("files");
            java.util.Iterator<String> it = f.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONArray a = f.getJSONArray(k);
                long off = a.getLong(0), len = a.getLong(1);
                if (off < HEADER || len < 0 || off + len > idxOff) throw new IOException("the pack file is damaged: " + k);
                files.put(k, new Entry(off, len));
            }
            Library.parse(root.getJSONArray("stations"), stations);
            for (Library.Station st : stations) st.pack = this;
        } catch (IOException e) {
            close();
            throw e;
        } catch (Exception e) {
            close();
            throw new IOException("invalid pack: " + e.getMessage());
        }
    }

    /** Positional read: does not move the file offset that MediaPlayer's duplicated descriptors share. */
    private void readFully(ByteBuffer buf, long pos) throws IOException {
        while (buf.hasRemaining()) {
            int n = channel.read(buf, pos + buf.position());
            if (n < 0) throw new IOException("unexpected end of file");
        }
    }

    List<Library.Station> stations() {
        return stations;
    }

    Entry entry(String path) {
        return files.get(path);
    }

    FileDescriptor fd() {
        return fd;
    }

    byte[] read(String path) throws IOException {
        Entry e = files.get(path);
        if (e == null || e.length > (16 << 20)) throw new IOException("missing: " + path);
        ByteBuffer b = ByteBuffer.allocate((int) e.length);
        readFully(b, e.offset);
        return b.array();
    }

    void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
        if (owner != null) {
            try {
                owner.close();
            } catch (IOException ignored) {
            }
        }
    }
}
