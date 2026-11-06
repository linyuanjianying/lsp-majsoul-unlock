-- lua5.1 tools/tests/test_auto_rematch.lua. Run from the repository root.
--
-- Black-box checks for the settlement/rematch adapter. Every fixture loads the
-- real hook-probe/src/main/assets/liqi_config/ui/MajsoulMaxAutoDiscard.lua with
-- dofile() and drives the public entry point __majmax_auto_tick(); only the
-- Unity/game globals are faked and no game traffic leaves this process.
--
-- Field names come from the dumped 4.0.16 Lua, NOT from the adapter:
--   UI_GameEnd            step is numeric (Start=0, LevelChange=30, Reward=50,
--                         Task=60, ActivityReward=70, Empty=80, Highlight=90,
--                         End=100). Btn_Next()/Btn_Again() are class methods
--                         reachable only through the class table, so
--                         methods are inherited through the instance metatable. locking and
--                         isDoAnimation gate the page; step 0 is the only page
--                         where the initial ranking is displayed.
--   UI_AnotherGameConfirm OnShow(callback, mode_id) stores mode_id in match_id,
--                         sets enable=true and holds locking for 0.2 s.
--   UI_MJReward_Activity  pages advance with index / TryClose() / ShowNext();
--                         no reward UI has a current_index field.
--
local asset = 'hook-probe/src/main/assets/liqi_config/ui/MajsoulMaxAutoDiscard.lua'
local count = 0

local function node(on)
    local n = { activeInHierarchy = on ~= false, interactable = true, clicks = 0 }
    n.gameObject = n
    n.transform = { gameObject = n }
    n.onClick = {
        Invoke = function(self) n.clicks = n.clicks + 1 end,
        RemoveAll = function(self) end,
        AddListener = function(self, fn) n.listener = fn end,
    }
    return n
end

local function fixture()
    -- The asset caches its module state in _G.__majmax_auto and hooks globals;
    -- every scenario starts from a clean slate.
    __majmax_auto = nil
    __majmax_auto_command = nil
    __majmax_auto_current = nil
    __majmax_auto_ack = nil
    package.loaded.cjson = nil
    local s = {
        acks = {}, again = 0, confirmAttempts = 0, cancelled = 0,
        nextPushed = {},
        heartbeat = '{"control":"mode","enabled":true,"game_uuid":"fixture"}',
    }

    __majmax_auto_command = function() return s.heartbeat end
    __majmax_auto_ack = function(payload) s.acks[#s.acks + 1] = payload end
    cjson = {
        encode = function(v) s.acks[#s.acks + 1] = v; return 'ack' end,
        decode = function() return s.command or { control = 'mode', enabled = true } end,
    }
    package.loaded.cjson = cjson

    UnityEngine = {
        Time = { realtimeSinceStartup = 0 },
        Screen = { width = 1920, height = 1080 },
        Camera = { main = nil, allCameras = {} },
    }
    UI_Win = nil; UI_HuleShow = nil; UI_ScoreChange = nil
    UI_ConfirmNewRound = nil; UI_HangUpWarn = nil; UI_GetReward = nil

    GameUtility = { EMJ_Mode = { play = 1, paipu = 2 }, E_PlayerOperation = {} }
    DesktopMgr = {
        IsActive = function() return true end,
        Inst = {
            gameing = false, mode = 1, duringReconnect = false, time_stopped = false,
            mainrole = {}, seat = 1,
            game_config = { category = 2, mode = { mode = 1 }, meta = { mode_id = 1 } },
        },
    }
    MJNetMgr = { Inst = { IsOK = function() return true end } }
    MJNetMgr.SendRequest = function() return 'original' end
    MJNetMgr.Inst.SendRequest = MJNetMgr.SendRequest

    -- UI_GameEnd class + one instance that inherits class methods through a
    -- metatable, exactly like UIBase:Inherit. inst.Btn_Again is nil, so a call
    -- site that reads the instance fails instead of silently no-oping.
    local inst = node(true)
    inst.step = 0
    inst.locking = false
    inst.isDoAnimation = false
    inst.tasklist = {}
    inst._index = 0
    inst.btn_next = node(true)
    inst.btn_again = node(true)
    inst.setStep = function(self, step) self.step = step end
    inst.GetActivityReward = function() return {} end
    inst._closeRankList = function() end
    local gameEnd = { Inst = inst, className = 'UI_GameEnd' }
    setmetatable(inst, { __index = gameEnd })

    gameEnd.Btn_Next = function(self)
        s.nextPushed[#s.nextPushed + 1] = self.step
        local order = { [0] = 30, [30] = 50, [50] = 60, [60] = 70, [70] = 80, [80] = 90, [90] = 100 }
        local nxt = order[self.step]
        if nxt then self.step = nxt end
    end
    gameEnd.Btn_Again = function(self)
        if self.locking then return end
        s.again = s.again + 1
        UI_AnotherGameConfirm:OnShow(function() end, DesktopMgr.Inst.game_config.meta.mode_id)
    end
    gameEnd.OnShow = function(self, callback, args)
        local target = (type(self) == 'table' and self.Inst) or self
        s.shown = (s.shown or 0) + 1
        target.game_end_result = args and args.game_end_result
        target.transform.gameObject.activeInHierarchy = true
        if callback then callback() end
    end
    _G.UI_GameEnd = gameEnd

    -- UI_AnotherGameConfirm: hidden and disabled until OnShow; the 0.2 s pop
    -- animation holds locking.
    local popupInst = node(false)
    popupInst.enable = false
    popupInst.locking = false
    local popupClass = { Inst = popupInst, className = 'UI_AnotherGameConfirm' }
    -- Both are class methods in the real class; counting here keeps the fake
    -- honest even when the adapter calls them with the class table as `self`.
    popupClass.Confirm = function(self)
        s.confirmAttempts = s.confirmAttempts + 1
        local target = (type(self) == 'table' and self.Inst) or self
        s.confirmed = (s.confirmed or 0) + 1
        target.activeInHierarchy = false
        target.transform.gameObject.activeInHierarchy = false
        target.enable = false
    end
    popupClass.Cancel = function(self)
        s.cancelled = s.cancelled + 1
        local target = (type(self) == 'table' and self.Inst) or self
        target.activeInHierarchy = false
        target.transform.gameObject.activeInHierarchy = false
        target.enable = false
    end
    popupClass.OnShow = function(self, callback, mode_id)
        local target = (type(self) == 'table' and self.Inst) or self
        if target.activeInHierarchy then return end
        target.match_id = type(mode_id) == 'table' and mode_id.mode_id or mode_id
        target.enable = true
        target.activeInHierarchy = true
        target.transform.gameObject.activeInHierarchy = true
        target.locking = true
        if callback then callback() end
        s.popupUnlocksAt = (UnityEngine.Time.realtimeSinceStartup or 0) + 0.2
    end
    setmetatable(popupInst, { __index = popupClass })
    _G.UI_AnotherGameConfirm = popupClass

    local reward = node(false)
    reward.index = 0
    reward.locking = false
    reward.TryClose = function(self)
        self.locking = true
        self.index = self.index + 1
        s.rewardIndex = self.index
    end
    _G.UI_MJReward_Activity = { Inst = reward, className = 'UI_MJReward_Activity' }

    dofile(asset)
    s.gameEnd, s.popup, s.reward = inst, popupInst, reward
    s.R = __majmax_auto.rematch
    return s
end

local function tick(s, at)
    if at then UnityEngine.Time.realtimeSinceStartup = at end
    __majmax_auto_tick()
    -- The popup clears its own 0.2 s lock on a game timer.
    local popup = s.popup
    if popup and popup.locking and s.popupUnlocksAt
            and UnityEngine.Time.realtimeSinceStartup >= s.popupUnlocksAt then
        popup.locking = false
        s.popupUnlocksAt = nil
    end
end

local function raise(s)
    s.gameEnd:OnShow(function() end,
        { game_end_result = { players = { { seat = 1 } } } })
end

-- One inert tick installs the OnShow hooks, then the settlement page is raised
-- so its show is observed (the adapter only drives an observed session).
local function prime(s)
    s.command = { control = 'mode', enabled = true, game_uuid = 'fixture' }
    tick(s, 0)
    raise(s)
end

local function command(s, patch)
    local c = { control = 'mode', enabled = true, autoRematch = true,
        delayMinMs = 0, delayMaxMs = 0 }
    if patch then for k, v in pairs(patch) do c[k] = v end end
    s.command = c
    return c
end

-- 0.1 s ticks (the adapter's poll cadence) from `from` for `span` seconds.
local function drive(s, from, span)
    local n = math.floor(span * 10 + 0.5)
    for i = 1, n do tick(s, from + i / 10) end
    return from + n / 10
end

local function states(s)
    local out = {}
    for _, row in ipairs(s.acks) do
        if type(row) == 'table' and type(row.state) == 'string' then
            out[#out + 1] = row.state .. (row.reason and row.reason ~= '' and (':' .. row.reason) or '')
        end
    end
    return out
end

local function countState(s, needle)
    local n = 0
    for _, row in ipairs(states(s)) do
        if row:find(needle, 1, true) then n = n + 1 end
    end
    return n
end

local function test(name, run)
    run(fixture())
    count = count + 1
    print('PASS ' .. name)
end

test('rematch is opt in: the boolean true is required', function(s)
    prime(s)
    command(s, { autoRematch = false })
    drive(s, 0, 8)
    assert(s.again == 0, 'autoRematch=false must not press again')
    s.command.autoRematch = 'true'
    drive(s, 10, 8)
    assert(s.again == 0, 'only the boolean true may enable rematch')
end)

test('an unobserved settlement page is never clicked before its show', function(s)
    command(s)
    drive(s, 0, 2)
    assert(s.again == 0 and #s.nextPushed == 0, 'no observed session, no press')
    assert(countState(s, 'again_invoked') == 0, 'no again state without a session')
    raise(s)
    drive(s, 3, 6)
    assert(s.again >= 1, 'an observed page may be driven')
end)

test('page lock and animation block every press', function(s)
    prime(s)
    command(s)
    s.gameEnd.locking = true
    drive(s, 0, 6)
    assert(s.again == 0 and #s.nextPushed == 0, 'locking page must not be driven')
    s.gameEnd.locking = false
    s.gameEnd.isDoAnimation = true
    drive(s, 10, 6)
    assert(s.again == 0 and #s.nextPushed == 0, 'animating page must not be driven')
end)

test('one settlement presses again once, never once per tick', function(s)
    prime(s)
    command(s)
    drive(s, 0, 8)
    assert(s.again >= 1, 'again must be pressed once the page is usable')
    assert(s.again == 1,
        'again must be pressed exactly once per settlement, got ' .. s.again)
end)

test('turning the switch off revokes a queued step', function(s)
    prime(s)
    command(s)
    s.command.enabled = false
    drive(s, 0, 8)
    assert(s.again == 0, 'enabled=false must revoke a queued step')
    s.command.enabled = true
    drive(s, 10, 8)
    assert(s.again >= 1, 'a re-enabled switch re-arms the page')
end)

test('an empty heartbeat revokes a queued step', function(s)
    prime(s)
    command(s)
    s.heartbeat = ''
    drive(s, 0, 8)
    assert(s.again == 0, 'an empty command must revoke a queued step')
    s.heartbeat = '{"control":"mode","enabled":true,"game_uuid":"fixture"}'
    drive(s, 10, 8)
    assert(s.again >= 1, 'a fresh command re-arms the page')
end)

test('a manual cancel is never re-opened or confirmed', function(s)
    prime(s)
    command(s)
    tick(s, 0.1)
    assert(s.popup.activeInHierarchy, 'the popup must be open')
    s.popup.locking = false
    UI_AnotherGameConfirm.Cancel(s.popup)
    assert(not s.popup.activeInHierarchy, 'cancel hides the popup')
    local agains, confirms = s.again, s.confirmAttempts
    drive(s, 6, 8)
    assert(s.confirmAttempts == confirms, 'a manual cancel must not be confirmed')
    assert(s.again == agains, 'a manual cancel must not re-open the popup')
end)

test('non ranked modes, reconnect and stopped time never press', function(s)
    prime(s)
    command(s)
    DesktopMgr.Inst.mode = 2 -- paipu
    drive(s, 0, 6)
    assert(s.again == 0, 'replay mode has no again path')
    DesktopMgr.Inst.mode = 1
    DesktopMgr.Inst.duringReconnect = true
    drive(s, 10, 6)
    assert(s.again == 0, 'reconnect gates the walk')
    DesktopMgr.Inst.duringReconnect = false
    DesktopMgr.Inst.time_stopped = true
    drive(s, 20, 6)
    assert(s.again == 0, 'a stopped clock gates the walk')
    DesktopMgr.Inst.time_stopped = false
    DesktopMgr.Inst.game_config.category = 1
    drive(s, 30, 6)
    assert(s.again == 0, 'non ranked categories are out of scope')
    DesktopMgr.Inst.game_config.category = 2
    DesktopMgr.Inst.game_config.mode.mode = 5
    drive(s, 40, 6)
    assert(s.again == 0, 'unsupported table modes stay untouched')
end)

test('without any settlement UI nothing is pressed', function(s)
    _G.UI_GameEnd = nil
    command(s)
    drive(s, 0, 8)
    assert(s.again == 0, 'nothing to press without UI_GameEnd')
    assert(s.confirmAttempts == 0, 'nothing to confirm without UI_GameEnd')
end)

test('a locked popup is never confirmed while its lock is held', function(s)
    prime(s)
    command(s)
    tick(s, 0.1)
    assert(s.popup.activeInHierarchy, 'popup open')
    local attempts = s.confirmAttempts
    -- Hold the lock well past the popup animation and confirm it is not pressed.
    s.popup.locking = true
    s.popupUnlocksAt = nil
    drive(s, 6, 6)
    assert(s.confirmAttempts == attempts,
        'a locked popup must not be confirmed, attempts ' .. s.confirmAttempts)
    s.popup.locking = false
    drive(s, 13, 3)
    assert(s.confirmAttempts == attempts + 1, 'the unlock lets exactly one confirm through')
end)

test('an again exception is reported without leaking text', function(s)
    prime(s)
    command(s)
    s.gameEnd.Btn_Again = function() error('bridge exploded') end
    drive(s, 0, 10)
    assert(countState(s, 'rematch_failed') >= 1, 'a failed again press must be reported')
    assert(s.confirmAttempts == 0, 'a failed again press must not be confirmed')
    for _, row in ipairs(states(s)) do
        assert(not row:find('exploded', 1, true), 'exception text must not travel')
    end
end)

test('a successful pcall is not a queued match', function(s)
    prime(s)
    command(s)
    local pressed = 0
    -- Btn_Again returns normally but opens no popup (the game's own meta check
    -- failed). The page must not be re-pressed: a returned pcall proves nothing.
    s.gameEnd.Btn_Again = function(self) pressed = pressed + 1 end
    drive(s, 0, 12)
    assert(pressed >= 1, 'the fake must be reached')
    assert(pressed == 1,
        'a no-op Again must not be re-pressed, got ' .. pressed)
    assert(s.confirmAttempts == 0, 'no popup means no confirm')
end)

test('an out of range step is never walked', function(s)
    prime(s)
    command(s)
    s.gameEnd.step = 250
    drive(s, 0, 8)
    assert(s.again == 0 and #s.nextPushed == 0, 'unknown steps fail closed')
end)

test('a reward page advances by its own index without repeat clicks', function(s)
    prime(s)
    command(s)
    s.gameEnd.step = 70
    s.reward.activeInHierarchy = true
    s.reward.transform.gameObject.activeInHierarchy = true
    s.reward.index = 1
    drive(s, 0, 6)
    local first = s.gameEnd.btn_next.clicks
    assert(first == 1, 'first activity reward page must be confirmed once')
    assert(s.again == 0, 'a reward page never opens the again popup')
    drive(s, 7, 4)
    assert(s.gameEnd.btn_next.clicks == first,
        'the same reward index must not be clicked twice, got ' ..
        (s.gameEnd.btn_next.clicks - first) .. ' extra clicks')
    s.reward.index = 2
    drive(s, 12, 1)
    assert(s.gameEnd.btn_next.clicks == first + 1, 'new activity index must receive its own confirmation')
end)

test('again remains available after reward step rather than only step zero', function(s)
    prime(s); command(s)
    s.gameEnd.step = 80
    drive(s, 0, 2)
    assert(s.again == 1 and s.confirmAttempts == 1)
end)

test('confirmation waits during reconnect and never changes match mode', function(s)
    prime(s); command(s)
    tick(s, 0.1)
    assert(s.again == 1)
    DesktopMgr.Inst.duringReconnect = true
    drive(s, 0.2, 1)
    assert(s.confirmAttempts == 0)
    DesktopMgr.Inst.duringReconnect = false
    DesktopMgr.Inst.game_config.meta.mode_id = 3
    drive(s, 2, 1)
    assert(s.confirmAttempts == 0)
end)

test('confirmation exception is latched and contains no exception text', function(s)
    prime(s); command(s)
    UI_AnotherGameConfirm.Confirm = function() error('private fixture detail') end
    drive(s, 0, 2)
    assert(countState(s, 'rematch_failed:confirm_exception') == 1)
    drive(s, 3, 2)
    assert(countState(s, 'rematch_failed:confirm_exception') == 1)
    for _, value in ipairs(states(s)) do assert(not value:find('private fixture detail',1,true)) end
end)

print(string.format('%d auto rematch checks passed', count))
