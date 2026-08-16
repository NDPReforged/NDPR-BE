#pragma once
// NDPR - LeviLamina Mod 入口（模板风格：load/enable/disable/unload）
#include "ll/api/event/ListenerBase.h"

class NDPRMod {
public:
    bool load();
    bool enable();
    bool disable();
    bool unload();

private:
    ll::event::ListenerPtr mJoinListener;
    ll::event::ListenerPtr mLeftListener;
};
