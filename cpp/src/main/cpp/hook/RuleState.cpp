#include <hook/RuleState.h>

#include <vector>

#include <android/android_log.h>
#include <mcpe/core/GameSDK.h>
#include <memory/SigScanner.h>

namespace RuleState {

HookRules::Rule rule;
bool hasRule = false;
std::string guestVersion;

void apply(const std::string &version, const std::string &rulesText) {
    guestVersion = version;
    GameSdk::setVersion(guestVersion.c_str());

    hasRule = false;
    GameSdk::setRule(nullptr, false);
    SigScanner::init(nullptr);
    rule = HookRules::Rule();
    if (rulesText.empty()) {
        LOGD("hook rules: none supplied for Minecraft %s, using the compiled-in data",
             guestVersion.c_str());
        return;
    }

    std::vector<std::string> errors;
    std::vector<HookRules::Rule> parsed = HookRules::parse(rulesText, &errors);
    for (size_t i = 0; i < errors.size(); i++) {
        LOGW("hook rules: %s", errors[i].c_str());
    }
    if (parsed.empty()) {
        LOGW("hook rules: the file parsed to zero rules (%zu byte(s)), using the compiled-in data",
             rulesText.size());
        return;
    }

    const HookRules::Rule *found = HookRules::select(parsed, guestVersion);
    if (found == nullptr) {
        LOGW("hook rules: none of the %zu rule(s) covers Minecraft %s; the RTTI path still runs "
             "and the byte signatures fall back to the compiled-in ones",
             parsed.size(), guestVersion.c_str());
        return;
    }
    rule = *found;
    hasRule = true;
    GameSdk::setRule(&rule, hasRule);
    SigScanner::init(&rule);  // same rule, now also pattern-resolvable
    LOGD("hook rules: rule '%s' active, vtable=%zu signature=%zu", rule.name.c_str(),
         rule.vtable.size(), rule.signatures.size());
}

}  // namespace RuleState
