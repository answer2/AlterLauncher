//
// Created by Aanswer_Dev on 2025/8/3.
//
#include <iostream>
#include <string>
using namespace std;

#ifndef ALTERLAUNCHER_MINECRAFTGAME_H
#define ALTERLAUNCHER_MINECRAFTGAME_H

class LevelSummary;
class AbstractScreen;
class Font;
class GameRenderer;
class GuiData;
class Screen;
class ScreenChooser;
class Minecraft;
class ClientInstance;
class Music;
class BuildActionIntention;
class Level;
class Mob;
class Dimension;
class Timer;
class Options;
class VoiceCommand;
class DirectionId;
class InputMode;
class Side;
class HolographicPlatform;
class VoiceSystem;
class Player;
class LocalPlayer;
class LevelSettings;
class SoundEngine;
class TextureAtlas;
class GameStore;
class GeometryGroup;
class SkinRepository;
class MinecraftTelemetry;
class ExternalServerFile;
class UIDefRepository;
class MobEffectsLayout;
class LevelRenderer;
class HolosceneRenderer;
class ParticleEngine;
class MinecraftInputHandler;
class MusicManager;
class LevelArchiver;
class SnoopClient;
class Entity;
class LevelRenderer;
class FocusImpact;
class TextureData;
class ResourcePacksInfoData;
class HoloUIInputMode;
class ResourcePackResponse;
class PushNotificationMessage;
class HoloGameMode;
class FilePathManager;
class Server;
class ResourcePackManager;
class ScreenshotOptions;

namespace mce
{
    class TextureGroup;
    class ImageBuffer;

}
namespace ui { class GameEventNotification; }
namespace Social
{
    class UserManager;
    class Multiplayer;
    class XboxLiveGameInfo;
    class GameConnectionInfo;
    namespace Telemetry
    {
        class TelemetryManager;
    }
}
class MinecraftGame {
public:
    virtual ~MinecraftGame();
public:
    //Methods
     MinecraftGame(int, char**);
    virtual void* sub_method();
    virtual void* sub_method1();
    virtual void* sub_method2(); // 有用 不知道是什么
    virtual void* sub_method3(); // 有用
    virtual void* sub_method4();
    virtual void* sub_method5();
    virtual void* sub_method6();
    virtual void* sub_method7();
    virtual void* sub_method8();
    virtual void* sub_method9();
    virtual void* sub_method10();
    virtual void* sub_method11();
    virtual void* sub_method12();
    virtual void* sub_method13();
    virtual void* sub_method14();
    virtual void* sub_method15();

//    virtual void* sub_method3();
//    virtual void* getPrimaryClientInstance();
//    virtual GuiData* getGuiData();
//    virtual Options* getOptions();
//    void releaseMouse();
//    void checkForPiracy();
//    void getScreenNames();
//    void getScreenStack();
public:
    static std::string WORLD_PATH;
    static int* GUI_SCALE_VALUES;
    static bool _hasInitedStatics;
    static std::string INTERACTION_FREQ_MS;
    static std::string RESOURCE_PACKS_SAVE_FILENAME;
};


#endif //ALTERLAUNCHER_MINECRAFTGAME_H
