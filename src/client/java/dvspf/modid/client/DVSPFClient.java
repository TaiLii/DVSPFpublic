package dvspf.modid.client;

import dvspf.modid.client.chat.PartyFinderChatListener;
import dvspf.modid.client.commands.PartyFinderCommands;
import dvspf.modid.client.config.DvspfConfig;
import dvspf.modid.client.partyfinder.HttpPartyRepository;
import dvspf.modid.client.partyfinder.PartyFinderManager;

import net.fabricmc.api.ClientModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DVSPFClient implements ClientModInitializer {

	private static final Logger LOG = LoggerFactory.getLogger("dvspf");

	@Override
	public void onInitializeClient() {
		// Load (or create) the user's config file. If they've filled in apiUrl,
		// swap the in-memory FakePartyRepository for the HTTP-backed one so all
		// guildmates pointed at the same backend see each other's listings.
		DvspfConfig cfg = DvspfConfig.load();
		if (cfg.isBackendConfigured()) {
			try {
				PartyFinderManager.get().setRepository(
					new HttpPartyRepository(cfg.apiUrl, cfg.apiKey, cfg.pollIntervalSeconds));
			} catch (RuntimeException e) {
				LOG.warn("Failed to initialise HTTP repo, falling back to fake data: {}", e.getMessage());
			}
		} else {
			LOG.info("No apiUrl in dvspf.json — using local FakePartyRepository (single-player testing).");
		}

		PartyFinderCommands.register();
		PartyFinderChatListener.register();
	}
}
