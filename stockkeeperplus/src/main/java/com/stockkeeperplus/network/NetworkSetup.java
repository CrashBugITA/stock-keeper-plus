package com.stockkeeperplus.network;

import com.stockkeeperplus.network.Payloads.ContentsRequest;
import com.stockkeeperplus.network.Payloads.ContentsResponse;
import com.stockkeeperplus.network.Payloads.GaugesRequest;
import com.stockkeeperplus.network.Payloads.GaugesResponse;
import com.stockkeeperplus.network.Payloads.StorageRequest;
import com.stockkeeperplus.network.Payloads.StorageResponse;
import com.stockkeeperplus.server.ServerQueries;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the messages as optional: a client and a server only use them if both have the mod,
 * so the mod can be installed on either side alone. The client checks with
 * {@code connection.hasChannel(...)} before asking.
 */
public final class NetworkSetup {
	private NetworkSetup() {}

	public static void register(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar("1")
			.optional();

		registrar.playToServer(GaugesRequest.TYPE, GaugesRequest.STREAM_CODEC, ServerQueries::onGauges);
		registrar.playToServer(StorageRequest.TYPE, StorageRequest.STREAM_CODEC, ServerQueries::onStorage);
		registrar.playToServer(ContentsRequest.TYPE, ContentsRequest.STREAM_CODEC, ServerQueries::onContents);

		// Client handlers are only referenced inside the lambdas, so a dedicated server never loads them.
		registrar.playToClient(GaugesResponse.TYPE, GaugesResponse.STREAM_CODEC,
			(payload, context) -> context.enqueueWork(() -> com.stockkeeperplus.client.ServerLink.receive(payload)));
		registrar.playToClient(StorageResponse.TYPE, StorageResponse.STREAM_CODEC,
			(payload, context) -> context.enqueueWork(() -> com.stockkeeperplus.client.ServerLink.receive(payload)));
		registrar.playToClient(ContentsResponse.TYPE, ContentsResponse.STREAM_CODEC,
			(payload, context) -> context.enqueueWork(() -> com.stockkeeperplus.client.ServerLink.receive(payload)));
	}
}
