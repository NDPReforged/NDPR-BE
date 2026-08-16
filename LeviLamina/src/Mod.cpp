// NDPR - LeviLamina Mod 入口实现
#include "Mod.h"

#include <string>

#include "ll/api/command/CommandHandle.h"
#include "ll/api/command/CommandRegistrar.h"
#include "ll/api/command/Optional.h"
#include "ll/api/event/EventBus.h"
#include "ll/api/event/player/PlayerDisconnectEvent.h"
#include "ll/api/event/player/PlayerJoinEvent.h"
#include "ll/api/mod/Mod.h"
#include "ll/api/mod/NativeMod.h"
#include "ll/api/mod/RegisterHelper.h"
#include "mc/server/commands/CommandOrigin.h"
#include "mc/server/commands/CommandOutput.h"
#include "mc/server/commands/CommandPermissionLevel.h"
#include "mc/server/commands/CommandRawText.h"
#include "mc/world/actor/ActorType.h"
#include "mc/world/actor/player/Player.h"

#include "NDPR.h"

namespace {

// 命令参数（全部可选，单 overload 分发）
struct NDPRArgs {
    ll::command::Optional<std::string>   sub;
    ll::command::Optional<std::string>   target;
    ll::command::Optional<CommandRawText> reason;
};

} // namespace

bool NDPRMod::load() {
    auto& self = *ll::mod::NativeMod::current();
    auto& logger = self.getLogger();
    ndpr::NDPR::getInstance().init(logger, self.getConfigDir(), self.getDataDir());
    return true;
}

bool NDPRMod::enable() {
    auto& self = *ll::mod::NativeMod::current();
    auto& logger = self.getLogger();

    // ---- 事件 ----
    auto& eventBus = ll::event::EventBus::getInstance();
    mJoinListener = eventBus.emplaceListener<ll::event::player::PlayerJoinEvent>(
        [](ll::event::player::PlayerJoinEvent& event) {
            ndpr::NDPR::getInstance().onPlayerJoin(event.self());
        });
    mLeftListener = eventBus.emplaceListener<ll::event::player::PlayerDisconnectEvent>(
        [](ll::event::player::PlayerDisconnectEvent& event) {
            ndpr::NDPR::getInstance().onPlayerLeft(event.self());
        });

    // ---- 命令 /ndpr ----
    auto& registrar = ll::command::CommandRegistrar::getInstance();
    auto& cmd = registrar.getOrCreateCommand("ndpr", "NDPR主命令", CommandPermissionLevel::Any);
    cmd.overload<NDPRArgs>()
        .optional("sub")
        .optional("target")
        .optional("reason")
        .execute([](CommandOrigin const& origin, CommandOutput& output, NDPRArgs const& args) {
            auto* entity = origin.getEntity();
            Player* player = (entity != nullptr && entity->isType(ActorType::Player))
                                 ? static_cast<Player*>(entity)
                                 : nullptr;
            // 管理员：命令来源权限等级 >= Admin(2)，控制台为 Host
            bool isAdmin = origin.getPermissionsLevel() >= CommandPermissionLevel::Admin;

            std::string sub = args.sub.has_value() ? args.sub.value() : "help";
            std::string target = args.target.has_value() ? args.target.value() : "";
            std::string reason = args.reason.has_value() ? args.reason.value().getText() : "";

            ndpr::NDPR::getInstance().handleCommand(player, isAdmin, sub, target, reason);
        });

    logger.info("NDPR LeviLamina 客户端已启用 (v2.1)");
    return true;
}

bool NDPRMod::disable() {
    auto& eventBus = ll::event::EventBus::getInstance();
    eventBus.removeListener(mJoinListener);
    eventBus.removeListener(mLeftListener);
    return true;
}

bool NDPRMod::unload() {
    ndpr::NDPR::getInstance().shutdown();
    return true;
}

static NDPRMod ndprMod;
LL_REGISTER_MOD(NDPRMod, ndprMod);
