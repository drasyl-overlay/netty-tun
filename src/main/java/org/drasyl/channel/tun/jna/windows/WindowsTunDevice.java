package org.drasyl.channel.tun.jna.windows;

import com.sun.jna.LastErrorException;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import org.drasyl.channel.tun.Tun4Packet;
import org.drasyl.channel.tun.Tun6Packet;
import org.drasyl.channel.tun.TunPacket;
import org.drasyl.channel.tun.jna.TunDevice;
import org.drasyl.channel.tun.jna.windows.Guid.GUID;
import org.drasyl.channel.tun.jna.windows.WinDef.DWORD;
import org.drasyl.channel.tun.jna.windows.Wintun.WINTUN_ADAPTER_HANDLE;
import org.drasyl.channel.tun.jna.windows.Wintun.WINTUN_SESSION_HANDLE;

import java.io.IOException;

import static java.util.Objects.requireNonNull;
import static org.drasyl.channel.tun.jna.windows.WinBase.INFINITE;
import static org.drasyl.channel.tun.jna.windows.WinError.ERROR_NO_MORE_ITEMS;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunAllocateSendPacket;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunCloseAdapter;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunCreateAdapter;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunEndSession;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunGetAdapterLUID;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunGetReadWaitEvent;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunReceivePacket;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunReleaseReceivePacket;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunSendPacket;
import static org.drasyl.channel.tun.jna.windows.Wintun.WintunStartSession;

/**
 * {@link TunDevice} implementation for Windows-based platforms.
 */
public final class WindowsTunDevice implements TunDevice {
    public static final WString TUNNEL_TYPE = new WString("drasyl");
    private final WINTUN_ADAPTER_HANDLE adapter;
    private final WINTUN_SESSION_HANDLE session;
    private final String name;
    private boolean closed;

    private WindowsTunDevice(final WINTUN_ADAPTER_HANDLE adapter,
                             final WINTUN_SESSION_HANDLE session,
                             final String name) {
        this.adapter = requireNonNull(adapter);
        this.session = requireNonNull(session);
        this.name = requireNonNull(name);
    }

    public static TunDevice open(String name) throws IOException {
        if (name == null) {
            name = "tun";
        }

        WINTUN_ADAPTER_HANDLE adapter = null;
        WINTUN_SESSION_HANDLE session = null;
        try {
            adapter = WintunCreateAdapter(new WString(name), TUNNEL_TYPE, GUID.newGuid());
            session = WintunStartSession(adapter, new DWORD(0x400000));
        }
        catch (final LastErrorException e) {
            if (session != null) {
                WintunEndSession(session);
            }

            if (adapter != null) {
                WintunCloseAdapter(adapter);
            }

            throw new IOException(e);
        }

        return new WindowsTunDevice(adapter, session, name);
    }

    @Override
    public String name() {
        return name;
    }

    @SuppressWarnings("java:S109")
    @Override
    public TunPacket readPacket(final ByteBufAllocator alloc) throws IOException {
        if (closed) {
            throw new IOException("Device is closed.");
        }

        while (true) {
            try {
                // read bytes
                final Pointer packetSizePointer = new Memory(Native.POINTER_SIZE);
                final Pointer packetPointer = WintunReceivePacket(session, packetSizePointer);

                // extract ip version
                final int ipVersion = packetPointer.getByte(0) >> 4;

                // shrink bytebuf to actual required size
                final int PacketSize = packetSizePointer.getInt(0);
                final ByteBuf byteBuf = alloc.buffer(PacketSize);
                byteBuf.writeBytes(packetPointer.getByteArray(0, PacketSize));
                WintunReleaseReceivePacket(session, packetPointer);

                if (ipVersion == 4) {
                    return new Tun4Packet(byteBuf);
                }
                else {
                    return new Tun6Packet(byteBuf);
                }
            }
            catch (final LastErrorException e) {
                if (e.getErrorCode() == ERROR_NO_MORE_ITEMS) {
                    Kernel32.INSTANCE.WaitForSingleObject(WintunGetReadWaitEvent(session), INFINITE);
                }
                else {
                    throw e;
                }
            }
        }
    }

    @Override
    public void writePacket(final ByteBufAllocator alloc, final TunPacket msg) throws IOException {
        if (closed) {
            throw new IOException("Device is closed.");
        }

        final DWORD packetSize = new DWORD(msg.content().readableBytes());
        final Pointer packetPointer = WintunAllocateSendPacket(session, packetSize);
        packetPointer.write(0, ByteBufUtil.getBytes(msg.content()), 0, msg.content().readableBytes());

        WintunSendPacket(session, packetPointer);
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;

            if (session != null) {
                WintunEndSession(session);
            }
            if (adapter != null) {
                WintunCloseAdapter(adapter);
            }
        }
    }

    public WindowsTunDevice setIpv4AddressAndNetmask(final String address, final int netmask) {
        final Pointer interfaceLuid = new Memory(8);
        WintunGetAdapterLUID(adapter, interfaceLuid);
        AddressAndNetmaskHelper.setIPv4AndNetmask(interfaceLuid, address, netmask);

        return this;
    }

    public WindowsTunDevice setIpv6AddressAndNetmask(final String address, final int netmask) {
        final Pointer interfaceLuid = new Memory(8);
        WintunGetAdapterLUID(adapter, interfaceLuid);
        AddressAndNetmaskHelper.setIPv6AndNetmask(interfaceLuid, address, netmask);

        return this;
    }
}
