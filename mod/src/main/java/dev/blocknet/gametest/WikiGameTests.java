// SPDX-License-Identifier: MIT
package dev.blocknet.gametest;

import com.google.gson.JsonObject;
import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.bridge.BridgeTools;
import dev.blocknet.knowledge.Wiki;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WikiGameTests {
    private static final String FIXTURE_ITEM = "ae2:controller";
    private static final String FIXTURE_SOURCE = "guide: ae2/";

    @GameTest(template = "arena", timeoutTicks = 100)
    public static void readsLoadedGuideAndRejectsNonsense(GameTestHelper helper) {
        TestArena.floor(helper);
        var bot = TestArena.spawnBot(helper, "BnWiki", new Vec3(4.5, 1, 4.5));
        var reports = new ArrayList<String>();
        JsonObject args = new JsonObject(); args.addProperty("query", "ME controller");
        BridgeTools.run(bot, BridgeTools.get("wiki"), args, new HashMap<>(), reports::add);
        helper.assertTrue(reports.size() == 1 && reports.getFirst().contains(FIXTURE_SOURCE), reports.toString());
        helper.assertTrue(reports.getFirst().split("\\s+").length < 120, "bounded snippets");
        var wiki = Wiki.get(bot.getServer());
        helper.assertTrue(wiki.search(FIXTURE_ITEM).contains(FIXTURE_SOURCE), "item id finds its guide");
        helper.assertTrue(wiki.search("zxqvplonk987654321").equals("nothing found"), "nonsense has no hits");
        helper.assertTrue(wiki.status().contains("GuideME:") && wiki.status().contains("Patchouli:"), wiki.status());
        Blocknet.LOGGER.info("WIKI_SAMPLE {}", reports.getFirst());
        BotManager.despawn("BnWiki"); helper.succeed();
    }
}
