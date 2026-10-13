//
// Created by chand on 6/27/2025.
// Credit to tozic
//
#pragma once
#include <glm/vec2.hpp>
#include <glm/vec4.hpp>
#include "../mce.hpp"
#include "../ClientInstance.hpp"
#include <imgui.h>
#include <optional>
#include <memory>
#include <string>

// Forward declarations
class HashedString;
class Font;
struct BedrockTextureData {};
struct CaretMeasureData
{
    int  mPosition;
    bool mIsSingleline;

    CaretMeasureData()
    {
        CaretMeasureData(0xFFFFFFFF, true);
    };

    CaretMeasureData(int position, bool singlelines)
    {
        this->mPosition = position;
        this->mIsSingleline = singlelines;
    };
};

struct TextMeasureData
{
public:
    TextMeasureData(float size, bool showShadow)
    {
        this->textSize = size;
        this->showShadow = showShadow;
        this->linePadding = 0;
        this->inObject = false;
    };

public:
    float textSize;
    int linePadding;
    bool showShadow;
    bool inObject;
    bool hideHyphen;
};
class MinecraftUIRenderContext {
public:
    ClientInstance *mClientInstance;  // this+0x8
private:
    virtual void Destructor() {};
public:
    virtual float getLineLength(class Font *font, std::string *str, float measureCalculation, bool calculateWidth) {};
private:
    virtual int getTextAlpha() {};

    virtual void setTextAlpha() {};

    virtual void drawDebugText() {};
public:
    //virtual void drawText(class Font* font, const float* pos, std::string* str, const float* colour, float alpha, float alinM, const TextMeasureData* textMeasureData, const CaretMeasureData* caretMeasureData) {};
    //virtual void drawText(Font& font, const float* pos, const std::string& str, const mce::Color& color, float alpha, const mce::Color& alinM, const glm::tvec2<float, 0>&, ui::TextAlignment, const TextMeasureData&, const CaretMeasureData&) {};
    virtual void drawText(Font *font, const glm::vec4 &pos, const std::string &text, const ImColor &color, float alpha,
                          unsigned int TextAlignment, const TextMeasureData &textData,
                          const CaretMeasureData &caretData) {

    }

    virtual void flushText(float deltaTime, std::optional<float> unk) {};
public:
    virtual void drawImage(BedrockTextureData *, glm::vec2 *ImagePos, glm::vec2 *ImageDimension, glm::vec2* uvPos,
                           glm::vec2 *uvSize, bool mDefaultTransformRotation) {};

    virtual void drawNineslice(mce::TexturePtr *const &texturePtr, void *NinesliceInfo) {};

    virtual void flushImages(ImColor color, float opacity, class HashedString &hashedString) {};

    virtual void beginSharedMeshBatch(uintptr_t ComponentRenderBatch) {};

    virtual void endSharedMeshBatch(float timeSinceLastFlush) {};
public:
    virtual void drawRectangle(glm::vec4 const &rect, ImColor const &colour, float alpha, int width) {};

    virtual void fillRectangle(glm::vec4 const &rect, ImColor const &colour, float alpha) {};
public:
    virtual auto increaseStencilRef() -> void {};

    virtual auto decreaseStencilRef() -> void {};

    virtual auto resetStencilRef() -> void {};

    virtual auto fillRectangleStencil(glm::vec4 const &position) -> void {};

    virtual auto enableScissorTest(glm::vec4 const &position) -> void {};

    virtual auto disableScissorTest() -> void {};

    virtual auto setClippingRectangle(glm::vec4 const &position) -> void {};

    virtual auto setFullClippingRectangle() -> void {};

    virtual auto saveCurrentClippingRectangle() -> void {};

    virtual auto restoreSavedClippingRectangle() -> void {};

    virtual auto getFullClippingRectangle() -> int {};

    virtual auto updateCustom(uintptr_t a1) -> void {};

    virtual auto renderCustom(uintptr_t a1, int a2, glm::vec4 const &position) -> void {};

    virtual auto cleanup() -> void {};

    virtual auto removePersistentMeshes() -> void {};

    virtual auto getTexture(class mce::TexturePtr *texture, void *resourceLocation) -> int {};

    virtual auto getZippedTexture(class TexturePtr *Path, class TexturePtr *ResourceLocation, bool a3) -> int {};

    virtual auto unloadTexture(class mce::TexturePtr *ResourceLocation) -> void {};

    virtual auto getUITextureInfo(class mce::TexturePtr *ResourceLocation, bool a2) -> int {};

    virtual auto touchTexture(class mce::TexturePtr *ResourceLocation) -> void {};

    virtual auto getMeasureStrategy(glm::vec2 const &) -> int {};

    virtual auto snapImageSizeToGrid(glm::vec2 const &) -> void {};

    virtual auto snapImagePositionToGrid(glm::vec2 const &) -> void {};

    virtual auto notifyImageEstimate(ULONG) -> void {};
};

// i swear to god e dater if those got leaked ur done for
class Textures // not an minecraft class this is our class
{
public:
    static inline BedrockTextureData* mTitle = nullptr;
    static inline BedrockTextureData* mSelectedHotbar = nullptr;
    static inline BedrockTextureData* mFire0 = nullptr;
    static inline BedrockTextureData* mFire1 = nullptr;
    static inline BedrockTextureData* mParticles = nullptr;
    static inline BedrockTextureData* mSliderBorder = nullptr;
    static inline BedrockTextureData* mXboxProfile = nullptr;
    static inline BedrockTextureData* mBellRinging = nullptr;
    static inline BedrockTextureData* mFocusBorder = nullptr;

    static void LoadAll(MinecraftUIRenderContext* renderCtx) {
        static bool initialized = false;
        if (initialized) return;

        LoadTexture("textures/ui/title", Textures::mTitle, renderCtx);
        LoadTexture("textures/ui/selected_hotbar_slot", Textures::mSelectedHotbar, renderCtx);
        LoadTexture("textures/blocks/fire_0", Textures::mFire0, renderCtx);
        LoadTexture("textures/blocks/fire_1", Textures::mFire1, renderCtx);
        LoadTexture("textures/particles/particles", Textures::mParticles, renderCtx);
        LoadTexture("textures/ui/bell_ringing", Textures::mBellRinging, renderCtx);
        LoadTexture("textures/ui/focus_border_white", Textures::mFocusBorder, renderCtx);
        LoadTexture("textures/ui/slider_border", Textures::mSliderBorder, renderCtx);;

        initialized = true;
    }

    static void LoadTexture(const std::string& path, BedrockTextureData*& out, MinecraftUIRenderContext* renderCtx) {
        if (!renderCtx) return;

        auto* texturePtr = new mce::TexturePtr();

        auto resLoc = std::make_unique<mce::ResourceLocation>(false, path);

        renderCtx->getTexture(texturePtr, resLoc.get());

        if (texturePtr->mClientTexture) {
            out = texturePtr->mClientTexture.get();
        }

        // Clean up the TexturePtr properly
        renderCtx->unloadTexture(texturePtr);
        delete texturePtr;
    }
};