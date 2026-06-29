package com.github.simcityexpansion.buildpack.network;

import java.util.ArrayList;
import java.util.List;

import com.github.simcityexpansion.SimcityExpansion;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server -&gt; client sync of the buildings from <b>activated</b> packs, so a player on a dedicated
 * server sees and can build them even though the files live only in the server's cache. Only
 * metadata is sent (small); the structure stays server-side and is resolved there at build time.
 *
 * @param buildings one entry per active building (across all active packs)
 */
public record ActivePacksSyncPacket(List<Entry> buildings) implements CustomPacketPayload {

  /** Metadata for one active building (mirrors what SimuKraft's build menu needs). */
  public record Entry(String category, String displayName, String size, String amount,
      String author, String metaFileName, String structureFileName) {}

  public static final Type<ActivePacksSyncPacket> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(SimcityExpansion.MODID, "active_packs_sync"));

  public static final StreamCodec<RegistryFriendlyByteBuf, ActivePacksSyncPacket> STREAM_CODEC =
      StreamCodec.of(ActivePacksSyncPacket::encode, ActivePacksSyncPacket::decode);

  @Override
  public Type<ActivePacksSyncPacket> type() {
    return TYPE;
  }

  private static void encode(RegistryFriendlyByteBuf buffer, ActivePacksSyncPacket packet) {
    buffer.writeVarInt(packet.buildings().size());
    for (Entry entry : packet.buildings()) {
      buffer.writeUtf(entry.category(), 64);
      buffer.writeUtf(entry.displayName(), 256);
      buffer.writeUtf(entry.size(), 64);
      buffer.writeUtf(entry.amount(), 64);
      buffer.writeUtf(entry.author(), 128);
      buffer.writeUtf(entry.metaFileName(), 256);
      buffer.writeUtf(entry.structureFileName(), 256);
    }
  }

  private static ActivePacksSyncPacket decode(RegistryFriendlyByteBuf buffer) {
    int count = buffer.readVarInt();
    List<Entry> entries = new ArrayList<>(Math.max(0, count));
    for (int i = 0; i < count; i++) {
      entries.add(new Entry(buffer.readUtf(64), buffer.readUtf(256), buffer.readUtf(64),
          buffer.readUtf(64), buffer.readUtf(128), buffer.readUtf(256), buffer.readUtf(256)));
    }
    return new ActivePacksSyncPacket(entries);
  }

  /** Client handler; applied on the main thread. The client-only logic is loaded lazily so this class stays server-safe. */
  public static void handle(ActivePacksSyncPacket packet, IPayloadContext context) {
    context.enqueueWork(() -> {
      if (FMLEnvironment.dist == Dist.CLIENT) {
        com.github.simcityexpansion.buildpack.client.ActivePacksClientSync.apply(packet);
      }
    });
  }
}
