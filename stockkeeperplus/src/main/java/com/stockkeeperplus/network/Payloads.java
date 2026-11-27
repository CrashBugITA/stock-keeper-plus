package com.stockkeeperplus.network;

import java.util.List;

import com.stockkeeperplus.StockKeeperPlus;
import com.stockkeeperplus.common.ContainerData;
import com.stockkeeperplus.common.GaugeData.Gauge;

import net.minecraft.core.GlobalPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Messages between the client and the optional server part. The client only sends requests while
 * the matching view is open; the server answers each one once. Requests carry no network id: the
 * server uses the Stock Keeper the player has open.
 */
public final class Payloads {
	private Payloads() {}

	static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String name) {
		return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(StockKeeperPlus.MOD_ID, name));
	}

	/** Client asks for every gauge of the open Stock Keeper's network. */
	public record GaugesRequest() implements CustomPacketPayload {
		public static final Type<GaugesRequest> TYPE = Payloads.typeOf("gauges_request");
		public static final StreamCodec<RegistryFriendlyByteBuf, GaugesRequest> STREAM_CODEC =
			StreamCodec.unit(new GaugesRequest());

		@Override
		public Type<GaugesRequest> type() {
			return TYPE;
		}
	}

	public record GaugesResponse(List<Gauge> gauges) implements CustomPacketPayload {
		public static final Type<GaugesResponse> TYPE = Payloads.typeOf("gauges_response");
		public static final StreamCodec<RegistryFriendlyByteBuf, GaugesResponse> STREAM_CODEC =
			Gauge.STREAM_CODEC.apply(ByteBufCodecs.list())
				.map(GaugesResponse::new, GaugesResponse::gauges);

		@Override
		public Type<GaugesResponse> type() {
			return TYPE;
		}
	}

	/** Client asks for every container of the open Stock Keeper's network, with how full it is. */
	public record StorageRequest() implements CustomPacketPayload {
		public static final Type<StorageRequest> TYPE = Payloads.typeOf("storage_request");
		public static final StreamCodec<RegistryFriendlyByteBuf, StorageRequest> STREAM_CODEC =
			StreamCodec.unit(new StorageRequest());

		@Override
		public Type<StorageRequest> type() {
			return TYPE;
		}
	}

	public record StorageResponse(List<ContainerData> containers) implements CustomPacketPayload {
		public static final Type<StorageResponse> TYPE = Payloads.typeOf("storage_response");
		public static final StreamCodec<RegistryFriendlyByteBuf, StorageResponse> STREAM_CODEC =
			ContainerData.STREAM_CODEC.apply(ByteBufCodecs.list())
				.map(StorageResponse::new, StorageResponse::containers);

		@Override
		public Type<StorageResponse> type() {
			return TYPE;
		}
	}

	/** An item and how many of it there are. */
	public record ItemCount(ItemStack item, int count) {
		public static final StreamCodec<RegistryFriendlyByteBuf, ItemCount> STREAM_CODEC = StreamCodec.of((buf, c) -> {
			ItemStack.STREAM_CODEC.encode(buf, c.item);
			buf.writeVarInt(c.count);
		}, buf -> new ItemCount(ItemStack.STREAM_CODEC.decode(buf), buf.readVarInt()));
	}

	/** Client asks what is inside some containers (one container, or a group), when hovering them. */
	public record ContentsRequest(List<GlobalPos> containers) implements CustomPacketPayload {
		public static final Type<ContentsRequest> TYPE = Payloads.typeOf("contents_request");
		public static final StreamCodec<RegistryFriendlyByteBuf, ContentsRequest> STREAM_CODEC =
			GlobalPos.STREAM_CODEC.apply(ByteBufCodecs.list())
				.<RegistryFriendlyByteBuf>cast()
				.map(ContentsRequest::new, ContentsRequest::containers);

		@Override
		public Type<ContentsRequest> type() {
			return TYPE;
		}
	}

	public record ContentsResponse(List<GlobalPos> containers, List<ItemCount> items) implements CustomPacketPayload {
		public static final Type<ContentsResponse> TYPE = Payloads.typeOf("contents_response");
		public static final StreamCodec<RegistryFriendlyByteBuf, ContentsResponse> STREAM_CODEC = StreamCodec.composite(
			GlobalPos.STREAM_CODEC.apply(ByteBufCodecs.list())
				.cast(), ContentsResponse::containers,
			ItemCount.STREAM_CODEC.apply(ByteBufCodecs.list()), ContentsResponse::items, ContentsResponse::new);

		@Override
		public Type<ContentsResponse> type() {
			return TYPE;
		}
	}
}
