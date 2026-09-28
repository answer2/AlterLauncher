#ifndef ALTER_LAUNCHER_MCPE_CLASS_TREE_H
#define ALTER_LAUNCHER_MCPE_CLASS_TREE_H

// ClassTree - umbrella header for the Minecraft Bedrock class hierarchy.
// Include this to get the full typed object model; individual headers are
// also includable for compile-time isolation.
#include <mcpe/ClassCommon.h>
#include <mcpe/Types.h>
#include <mcpe/ClientInstance.h>
#include <mcpe/MinecraftGame.h>
#include <mcpe/Actor.h>
#include <mcpe/GuiData.h>
#include <mcpe/LevelRenderer.h>
#include <mcpe/LevelRendererPlayer.h>
#include <mcpe/Network.h>
#include <mcpe/Level.h>
#include <mcpe/Dimension.h>
#include <mcpe/BlockSource.h>
#include <mcpe/Block.h>
#include <mcpe/GameMode.h>
#include <mcpe/Options.h>
#include <mcpe/Camera.h>
#include <mcpe/Item.h>
#include <mcpe/ItemStack.h>
#include <mcpe/CompoundTag.h>
#include <mcpe/ContainerScreenController.h>
#include <mcpe/MoveInputHandler.h>
#include <mcpe/ClientInstanceScreenModel.h>
#include <mcpe/MobEffectInstance.h>

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
