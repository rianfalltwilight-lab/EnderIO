package com.enderio.enderio.tests.conduits;

import com.enderio.enderio.api.EnderIORegistries;
import com.enderio.enderio.api.conduits.network.node.NodeData;
import com.enderio.enderio.api.conduits.network.node.NodeDataType;
import com.enderio.enderio.content.conduits.network.ConduitNetworkSavedData;
import com.enderio.enderio.init.EIOConduitTypes;
import com.enderio.enderio.init.EIOConduits;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(EphemeralTestServerProvider.class)
class ConduitSaveTests {
    @Test
    void transfersBetweenSavesKeepRoundRobinProgress(MinecraftServer server) {
        var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var saved = ConduitNetworkSavedData.CODEC.parse(ops, JsonParser.parseString("""
            [{"conduit_type":"enderio:item","nodes":[{"pos":[0,0,0],"conduit":"enderio:item"}],"edges":[]}]
            """)).getOrThrow();
        var conduit = server.registryAccess().lookupOrThrow(EnderIORegistries.Keys.CONDUIT).getOrThrow(EIOConduits.ITEM);
        var node = saved.claimNode(conduit, BlockPos.ZERO);
        assertNotNull(node);
        var data = node.getOrCreateNodeData(EIOConduitTypes.NodeData.ITEM.get());
        data.setIndex(Direction.UP, 1);
        var first = ConduitNetworkSavedData.CODEC.encodeStart(ops, saved).getOrThrow();
        saved.setDirty(false);
        assertFalse(saved.isDirty());

        data.setIndex(Direction.UP, 1);
        assertFalse(saved.isDirty());
        data.setIndex(Direction.UP, 2);
        assertTrue(saved.isDirty());
        var second = ConduitNetworkSavedData.CODEC.encodeStart(ops, saved).getOrThrow();
        assertNotEquals(first, second);
        var restored = ConduitNetworkSavedData.CODEC.parse(ops, second).getOrThrow().claimNode(conduit, BlockPos.ZERO);
        assertEquals(2, restored.getNodeData(EIOConduitTypes.NodeData.ITEM.get()).getIndex(Direction.UP));

        // Addons can mutate persistent data through a retained reference without calling markDirty.
        var customData = mock(NodeData.class);
        var customType = mock(NodeDataType.class);
        doReturn(customType).when(customData).type();
        when(customType.isPersistent()).thenReturn(true);
        node.setNodeData(customData);
        saved.setDirty(false);
        assertTrue(saved.isDirty());
    }
}
