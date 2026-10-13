#ifndef ALTER_LAUNCHER_MCPE_CLASS_TREE_H
#define ALTER_LAUNCHER_MCPE_CLASS_TREE_H

// ClassTree - umbrella header for the Minecraft Bedrock class hierarchy.
// Include this to get the full typed object model; individual headers are
// also includable for compile-time isolation.
#include <mcpe/core/ClassCommon.h>
#include <mcpe/core/Types.h>
#include <mcpe/client/ClientInstance.h>
#include <mcpe/client/MinecraftGame.h>
#include <mcpe/actor/Actor.h>
#include <mcpe/client/GuiData.h>
#include <mcpe/client/LevelRenderer.h>
#include <mcpe/client/LevelRendererPlayer.h>
#include <mcpe/network/Network.h>
#include <mcpe/network/Packet.h>
#include <mcpe/world/Level.h>
#include <mcpe/world/Dimension.h>
#include <mcpe/world/BlockSource.h>
#include <mcpe/world/Block.h>
#include <mcpe/actor/GameMode.h>
#include <mcpe/client/Options.h>
#include <mcpe/client/Camera.h>
#include <mcpe/inventory/Item.h>
#include <mcpe/inventory/ItemStack.h>
#include <mcpe/inventory/CompoundTag.h>
#include <mcpe/actor/ContainerScreenController.h>
#include <mcpe/actor/MoveInputHandler.h>
#include <mcpe/client/ClientInstanceScreenModel.h>
#include <mcpe/actor/MobEffectInstance.h>

namespace mcpe {

// GameTree: convenience aggregate that resolves all top-level handles in one
// shot. Construct after GameSdk::clientInstance() is non-zero.
struct GameTree {
    Handle clientInstance;
    Handle guiData;
    Handle minecraftGame;
    Handle localPlayer;
    Handle levelRenderer;
    Handle level;
    Handle camera;
    Handle options;
    Handle region;
    Handle packetSender;

    GameTree();
    bool ready() const { return clientInstance.valid(); }
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_CLASS_TREE_H
