#include "HookRules.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>

#include <sstream>

#include "android/android_log.h"

namespace HookRules {

namespace {

std::string trim(const std::string &text) {
    size_t begin = 0;
    while (begin < text.size() && isspace((unsigned char) text[begin])) begin++;
    size_t end = text.size();
    while (end > begin && isspace((unsigned char) text[end - 1])) end--;
    return text.substr(begin, end - begin);
}

// Only digits and dots take part in the numeric comparison; everything after
// the first non-numeric character is the tie-breaker.
bool numericPrefix(const std::string &version, std::vector<unsigned long> *parts,
                   std::string *tail) {
    std::string current;
    size_t i = 0;
    for (; i < version.size(); i++) {
        char c = version[i];
        if (isdigit((unsigned char) c)) {
            current.push_back(c);
        } else if (c == '.') {
            if (current.empty()) return false;
            parts->push_back(strtoul(current.c_str(), nullptr, 10));
            current.clear();
        } else {
            break;
        }
    }
    if (!current.empty()) {
        parts->push_back(strtoul(current.c_str(), nullptr, 10));
    }
    *tail = version.substr(i);
    return !parts->empty();
}

std::string describe(const Rule &rule) {
    std::ostringstream out;
    out << rule.name << " [" << (rule.minVersion.empty() ? "*" : rule.minVersion) << ", "
        << (rule.maxVersion.empty() ? "*" : rule.maxVersion) << "] " << rule.vtable.size()
        << " vtable / " << rule.signatures.size() << " pattern / " << rule.offsets.size()
        << " offset / " << rule.dataOffsets.size() << " data";
    return out.str();
}

}  // namespace

int compareVersions(const std::string &left, const std::string &right) {
    std::vector<unsigned long> leftParts, rightParts;
    std::string leftTail, rightTail;
    bool leftOk = numericPrefix(left, &leftParts, &leftTail);
    bool rightOk = numericPrefix(right, &rightParts, &rightTail);
    if (!leftOk || !rightOk) {
        // Not a version we can order numerically; fall back to a plain compare so
        // the result is at least stable.
        return left.compare(right);
    }
    size_t count = leftParts.size() > rightParts.size() ? leftParts.size() : rightParts.size();
    for (size_t i = 0; i < count; i++) {
        unsigned long a = i < leftParts.size() ? leftParts[i] : 0UL;
        unsigned long b = i < rightParts.size() ? rightParts[i] : 0UL;
        if (a != b) return a < b ? -1 : 1;
    }
    return leftTail.compare(rightTail);
}

bool Rule::covers(const std::string &version) const {
    if (version.empty()) return false;
    if (!minVersion.empty() && compareVersions(version, minVersion) < 0) return false;
    if (!maxVersion.empty() && compareVersions(version, maxVersion) > 0) return false;
    return true;
}

bool Rule::lookupVtable(const std::string &key, std::string *className, size_t *slot) const {
    std::map<std::string, std::string>::const_iterator found = vtable.find(key);
    if (found == vtable.end()) return false;
    size_t sep = found->second.rfind(':');
    if (sep == std::string::npos || sep + 1 >= found->second.size()) return false;
    std::string name = trim(found->second.substr(0, sep));
    std::string index = trim(found->second.substr(sep + 1));
    if (name.empty()) return false;
    for (size_t i = 0; i < index.size(); i++) {
        if (!isdigit((unsigned char) index[i])) return false;
    }
    *className = name;
    *slot = strtoul(index.c_str(), nullptr, 10);
    return true;
}

std::string Rule::lookupSignature(const std::string &key) const {
    std::map<std::string, std::string>::const_iterator found = signatures.find(key);
    return found == signatures.end() ? std::string() : found->second;
}

bool Rule::lookupOffset(const std::string &key, uintptr_t *rva) const {
    std::map<std::string, uintptr_t>::const_iterator found = offsets.find(key);
    if (found == offsets.end()) return false;
    *rva = found->second;
    return true;
}

// Object-relative field pins (dat.<key>=d0x458): same storage shape as offsets
// but a different namespace, because an offset like 0x458 is meaningless as a
// library RVA and dangerous to confuse with one.
bool Rule::lookupDataOffset(const std::string &key, uintptr_t *out) const {
    std::map<std::string, uintptr_t>::const_iterator found = dataOffsets.find(key);
    if (found == dataOffsets.end()) return false;
    *out = found->second;
    return true;
}

// `0x923a5d4` or `923a5d4` are hex - the form every one of these comes out of a
// disassembler in - while a value with no letters at all is read as decimal.
// Anything else is rejected, so a typo lands in the parse errors instead of
// quietly becoming an offset of zero.
static bool parseOffset(const std::string &text, uintptr_t *out) {
    std::string body = trim(text);
    if (body.empty()) return false;
    int base = 10;
    if (body.size() > 2 && body[0] == '0' && (body[1] == 'x' || body[1] == 'X')) {
        body = body.substr(2);
        base = 16;
    }
    for (size_t i = 0; i < body.size(); i++) {
        char c = body[i];
        bool decimal = isdigit((unsigned char) c);
        bool hexLetter = (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        if (!decimal && !hexLetter) return false;
        if (hexLetter) base = 16;
    }
    char *stop = nullptr;
    unsigned long long value = strtoull(body.c_str(), &stop, base);
    if (stop == nullptr || *stop != '\0' || value == 0) return false;
    *out = static_cast<uintptr_t>(value);
    return true;
}

std::vector<Rule> parse(const std::string &text, std::vector<std::string> *errors) {
    std::vector<Rule> rules;
    std::istringstream stream(text);
    std::string line;
    size_t lineNumber = 0;
    bool inRule = false;

    while (std::getline(stream, line)) {
        lineNumber++;
        // A `\r` from a file edited on Windows would otherwise end up inside the
        // pattern and make every byte scan fail on the last token.
        if (!line.empty() && line[line.size() - 1] == '\r') line.erase(line.size() - 1);
        std::string body = trim(line);
        if (body.empty() || body[0] == '#' || body[0] == ';') continue;

        if (body[0] == '[') {
            size_t end = body.find(']');
            if (end == std::string::npos) {
                if (errors) {
                    std::ostringstream out;
                    out << "line " << lineNumber << ": unterminated section header";
                    errors->push_back(out.str());
                }
                continue;
            }
            std::string header = trim(body.substr(1, end - 1));
            if (header.compare(0, 5, "rule ") != 0 || header.size() <= 5) {
                if (errors) {
                    std::ostringstream out;
                    out << "line " << lineNumber << ": expected [rule <name>], got [" << header
                        << "]";
                    errors->push_back(out.str());
                }
                inRule = false;
                continue;
            }
            rules.push_back(Rule());
            rules.back().name = trim(header.substr(5));
            inRule = true;
            continue;
        }

        if (!inRule) {
            if (errors) {
                std::ostringstream out;
                out << "line " << lineNumber << ": key/value outside of any [rule]";
                errors->push_back(out.str());
            }
            continue;
        }

        size_t sep = body.find('=');
        if (sep == std::string::npos) {
            if (errors) {
                std::ostringstream out;
                out << "line " << lineNumber << ": expected key = value, got " << body;
                errors->push_back(out.str());
            }
            continue;
        }
        Rule &rule = rules.back();
        std::string key = trim(body.substr(0, sep));
        std::string value = trim(body.substr(sep + 1));

        if (key == "min") {
            rule.minVersion = value;
        } else if (key == "max") {
            rule.maxVersion = value;
        } else if (key.compare(0, 7, "vtable.") == 0) {
            rule.vtable[trim(key.substr(7))] = value;
        } else if (key.compare(0, 4, "sig.") == 0) {
            rule.signatures[trim(key.substr(4))] = value;
        } else if (key.compare(0, 4, "dat.") == 0) {
            uintptr_t dataOff = 0;
            std::string marked = trim(value);
            bool ok = !marked.empty() && (marked[0] == 'd' || marked[0] == 'D') &&
                      parseOffset(marked.substr(1), &dataOff);
            if (ok) {
                rule.dataOffsets[trim(key.substr(4))] = dataOff;
            } else if (errors) {
                std::ostringstream out;
                out << "line " << lineNumber << ": data offset " << value
                    << " must look like d0x458";
                errors->push_back(out.str());
            }
        } else if (key.compare(0, 4, "off.") == 0) {
            uintptr_t rva = 0;
            if (parseOffset(value, &rva)) {
                rule.offsets[trim(key.substr(4))] = rva;
            } else if (errors) {
                std::ostringstream out;
                out << "line " << lineNumber << ": offset " << value << " is not a number";
                errors->push_back(out.str());
            }
        } else if (errors) {
            std::ostringstream out;
            out << "line " << lineNumber << ": unknown key " << key;
            errors->push_back(out.str());
        }
    }
    return rules;
}

const Rule *select(const std::vector<Rule> &rules, const std::string &version) {
    for (size_t i = 0; i < rules.size(); i++) {
        if (rules[i].covers(version)) {
            LOGD("hook rules: Minecraft %s is covered by rule %s", version.c_str(),
                 describe(rules[i]).c_str());
            return &rules[i];
        }
    }
    return nullptr;
}

}  // namespace HookRules