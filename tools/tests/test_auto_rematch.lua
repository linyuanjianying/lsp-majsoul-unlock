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

    s.rewardClass = { Inst = reward, className = 'UI_MJReward_Activity' }
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

test('rematch runs with unattended disabled', function(s)
    prime(s); command(s, { enabled = false, autoRematch = true })
    drive(s, 0, 8)
    assert(s.again == 1, 'independent rematch must press again')
    assert(s.confirmAttempts >= 1, 'independent rematch must confirm')
    assert(__majmax_auto.armed == false, 'rematch must not arm automatic card play')
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

test('native ranking lock and hidden buttons block premature presses', function(s)
    prime(s); command(s)
    s.gameEnd.locking = true
    drive(s, 0, 6)
    assert(s.again == 0 and #s.nextPushed == 0)
    s.gameEnd.locking = false
    s.gameEnd.btn_again.activeInHierarchy = false
    s.gameEnd.btn_next.activeInHierarchy = false
    drive(s, 10, 6)
    assert(s.again == 0 and #s.nextPushed == 0)
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
    s.command.autoRematch = false
    drive(s, 0, 8)
    assert(s.again == 0, 'autoRematch=false must revoke a queued step')
    s.command.autoRematch = true
    -- Same page, same settlement: re-enabling picks the still-open result page back up.
    drive(s, 0, 8)
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

test('a reused settlement singleton can start a fresh rematch session', function(s)
    prime(s)
    command(s)
    drive(s, 0, 3)
    assert(s.again == 1 and s.confirmAttempts == 1, 'first settlement must complete')

    -- The game reuses UI_GameEnd.Inst for a later match result.
    s.popup.locking = false
    s.gameEnd.step = 0
    s.gameEnd.btn_again.activeInHierarchy = true
    s.gameEnd.btn_next.activeInHierarchy = true
    raise(s)
    drive(s, 4, 3)
    assert(s.again == 2, 'a new OnShow on the reused instance must get a fresh session')
    assert(s.confirmAttempts == 2, 'the second result popup must be confirmed once')
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

-- ---------------------------------------------------------------------------
-- 0.13.1 regressions. The user report behind these checks is "auto rematch does
-- nothing and unattended mode is broken after 550a671". Three independent causes
-- are pinned here; each one reproduces the device symptom on its own.
-- ---------------------------------------------------------------------------

-- ToLua resolves a global that is not yet require'd by raising instead of
-- returning nil. The settlement walk sits in the per-frame tick, so a single
-- unguarded `local c = UI_GameEnd` throws a hundred times a second while the page
-- is closed. The wrapper then answered with rematch_failed/adapter_exception and
-- `return`, and the old `R.cancel('adapter_exception')` answer additionally skipped
-- the whole unattended continuation/execute path for that tick.
local function lazy(t)
    return setmetatable(t, { __index = function(_, key)
        error("lazy global '" .. tostring(key) .. "' not loaded", 2)
    end })
end

test('a closed settlement page cannot throw through the world tick', function(s)
    -- Every page the walk looks at is still unloaded, exactly like a fresh attach
    -- before its window exists.
    _G.UI_GameEnd, _G.UI_AnotherGameConfirm = nil, nil
    -- Reading a missing global now raises, as ToLua does.
    local saved = getfenv(0)
    setmetatable(_G, { __index = function(_, key)
        error("lazy global '" .. tostring(key) .. "' not loaded", 2)
    end })
    command(s)
    local ok, err = pcall(drive, s, 0, 3)
    setmetatable(_G, nil)
    assert(ok, 'the tick raised: ' .. tostring(err))
    assert(countState(s, 'rematch_failed') == 0,
        'a closed page must not be reported as a rematch failure')
    assert(countState(s, 'adapter_exception') == 0,
        'a closed page must not report adapter_exception')
end)

test('a late attached session is recoverable without a new show', function(s)
    -- Module loaded after UI_GameEnd: OnShow was never wrapped, so the settlement
    -- is already up when the adapter arrives. The live page still carries its
    -- game_end_result, and the walk must adopt it instead of waiting forever.
    command(s)
    drive(s, 0, 2)
    s.gameEnd.game_end_result = { players = { { seat = 1 } } }
    s.gameEnd.transform.gameObject.activeInHierarchy = true
    s.R.session = nil
    drive(s, 3, 8)
    assert(s.again >= 1, 'a late session on a live page must be driven')
    assert(countState(s, 'rematch_failed') == 0)
end)

test('the settlement walk wins over the previous match continuation', function(s)
    -- UI_ConfirmNewRound is up at the same time as the final result page: pressing
    -- its confirm closes the round and ends the match, which is exactly how the
    -- rematch used to be thrown away. The walk must win, and the confirmation must
    -- be held back while the settlement is live.
    prime(s)
    local confirm = node(true)
    local confirmUi = node(true)
    confirmUi.locking = false
    confirmUi.isDoAnimation = false
    confirmUi.btn_confirm = confirm
    confirmUi.Btn_Confirm = function(self) s.roundConfirmed = (s.roundConfirmed or 0) + 1 end
    _G.UI_ConfirmNewRound = { Inst = confirmUi, OnShow = function(self) end }
    command(s)
    drive(s, 0, 6)
    -- The round confirmation waits its own delay first; the settlement must get a
    -- chance before that button is pressed, so keep driving well past both delays.
    drive(s, 6, 6)
    assert(confirm.clicks == 0,
        'the round confirmation must not pre-empt the settlement page')
    assert(s.again >= 1, 'the settlement page must still be driven')
end)

test('a raised settlement walk still lets the unattended path run', function(s)
    -- The settlement walk breaks (a bridge call raises mid-step). The tick must
    -- report a bounded rematch failure, stop the walk, and still continue the
    -- previous match work in the same tick: a broken settlement may never take the
    -- play loop down with it.
    command(s)
    -- The page is raised without OnShow, the way a module that attached after the
    -- result page already opened sees it (attachLive adopts it from game_end_result).
    s.gameEnd.game_end_result = { players = { { seat = 1 } } }
    s.gameEnd.transform.gameObject.activeInHierarchy = true
    local confirm = node(true)
    local confirmUi = node(true)
    confirmUi.btn_confirm = confirm
    confirmUi.Btn_Confirm = function(self) s.roundConfirmed = (s.roundConfirmed or 0) + 1 end
    _G.UI_ConfirmNewRound = { Inst = confirmUi, OnShow = function(self) end }
    s.gameEnd.step = 70
    -- A bridge object the walk must read raises while it inspects it (Unity
    -- property access on a destroyed page is the real-world shape of this). The
    -- next page is the activity reward page, so the walk reads its `locking` flag.
    local brokenReward = setmetatable({}, { __index = function()
        error('bridge exploded')
    end })
    _G.UI_MJReward_Activity = { Inst = brokenReward, className = 'UI_MJReward_Activity' }
    s.reward.activeInHierarchy = true
    s.reward.transform.gameObject.activeInHierarchy = true
    -- One tick: the settlement walk raises, so the tick reports that, skips to the
    -- rest of the work, and the unattended continuation still presses its button.
    tick(s, 0.3)
    assert(countState(s, 'rematch_failed:adapter_exception') == 1,
        'the raised settlement step must be reported once')
    assert(s.roundConfirmed == 1,
        'the unattended continuation must still run in the same tick')
    assert(countState(s, 'continued') == 1, 'the continuation must be acknowledged')
    assert(s.R.session == nil or s.R.session.stopped == true,
        'the broken walk must stop itself')
    -- The broken settlement is not retried: later ticks only continue the match.
    for i = 1, 5 do tick(s, 0.3 + i / 10) end
    assert(countState(s, 'rematch_failed:adapter_exception') == 1,
        'a raised settlement must not be retried on every tick')
    s.reward.activeInHierarchy = false
    s.reward.transform.gameObject.activeInHierarchy = false
    _G.UI_MJReward_Activity = s.rewardClass
end)

test('unknown pages stop the walk without blocking the match', function(s)
    -- An unlisted page (step 250 in this fixture) is not this build's settlement:
    -- nothing is pressed, nothing is failed, and the play loop keeps its own path.
    prime(s)
    local confirm = node(true)
    local confirmUi = node(true)
    confirmUi.btn_confirm = confirm
    confirmUi.Btn_Confirm = function(self) s.roundConfirmed = (s.roundConfirmed or 0) + 1 end
    _G.UI_ConfirmNewRound = { Inst = confirmUi, OnShow = function(self) end }
    s.gameEnd.step = 250
    command(s)
    drive(s, 0, 6)
    assert(s.again == 0 and #s.nextPushed == 0, 'an unknown page must not be pressed')
    assert(countState(s, 'rematch_failed') == 0, 'an unknown page is not a failure')
    assert(countState(s, 'rematch_paused') == 0, 'an unknown page is not a cancel')
end)

test('a missing again method is a bounded refusal', function(s)
    -- A UI class that does not expose Btn_Again (renamed or partially loaded) must
    -- not be reported as a tick crash and must never fall through to Next.
    prime(s); command(s)
    -- The instance now reports a page with no usable again button.
    s.gameEnd.btn_again = setmetatable({}, { __index = function() return nil end })
    s.gameEnd.btn_again_active = false
    s.gameEnd.btn_next = node(false)
    s.gameEnd.Btn_Again = nil
    local ok, err = pcall(drive, s, 0, 4)
    assert(ok, 'a missing method raised: ' .. tostring(err))
    assert(#s.nextPushed == 0, 'a missing again must not fall through to next')
    assert(s.confirmAttempts == 0, 'no popup means no confirm')
    assert(countState(s, 'rematch_failed:adapter_exception') == 0,
        'a missing button is a closed page, not a tick crash')
end)

test('a missing confirm method is a bounded refusal', function(s)
    -- The confirmation window exists but the class lost Confirm: the walk stops
    -- that settlement instead of crashing or retrying it every tick.
    prime(s); command(s)
    UI_AnotherGameConfirm.Confirm = nil
    tick(s, 0.1)
    assert(s.popup.activeInHierarchy, 'the popup must be open')
    s.popup.locking = false
    local ok, err = pcall(drive, s, 1, 4)
    assert(ok, 'a missing confirm raised: ' .. tostring(err))
    assert(s.confirmAttempts == 0, 'nothing may be confirmed without Confirm')
    assert(countState(s, 'rematch_failed:adapter_exception') == 0,
        'a missing confirm is a refusal, not a tick crash')
end)

-- ---------------------------------------------------------------------------
-- 0.15.9 regressions. The user report behind these checks is "the first match
-- continues by itself, from the second match on nothing happens while the
-- auto-rematch switch still shows as on". One cause is pinned here: the walk
-- anchored every settlement from UI_GameEnd:OnShow only, and its finished
-- session (done/stopped) refused every other tick for good.
-- ---------------------------------------------------------------------------

test('a later settlement is driven even when its show never reaches the module', function(s)
    -- The adapter's own Confirm closes the result page, and the next match's page is
    -- raised through a path the class hook cannot see (the game's OnShow raised
    -- before the wrapper could observe, or the reused singleton was re-raised
    -- outside it). The page still carries the new result, so the walk must anchor
    -- on it instead of answering every tick for a settlement that is already over.
    prime(s)
    command(s)
    drive(s, 0, 3)
    assert(s.again == 1 and s.confirmAttempts == 1, 'the first settlement must complete')
    -- Between the two matches the page is closed and the table is live again.
    DesktopMgr.Inst.gameing = true
    s.gameEnd.transform.gameObject.activeInHierarchy = false
    drive(s, 3, 1)
    -- The second match ends: same singleton, its own result, no observed show.
    DesktopMgr.Inst.gameing = false
    s.gameEnd.step = 0
    s.gameEnd.btn_again.activeInHierarchy = true
    s.gameEnd.btn_next.activeInHierarchy = true
    s.gameEnd.game_end_result = { players = { { seat = 1 } } }
    s.gameEnd.transform.gameObject.activeInHierarchy = true
    drive(s, 5, 4)
    assert(s.again == 2, 'a later settlement must be driven, got ' .. s.again)
    assert(s.confirmAttempts == 2, 'its confirmation must run too, got ' .. s.confirmAttempts)
end)

test('a manual cancel keeps its page refused without locking out the next match', function(s)
    -- The cancelled page stays refused for as long as it shows the result the user
    -- took over. The next match's result, however, is a settlement of its own and
    -- must not inherit that refusal.
    prime(s)
    command(s)
    tick(s, 0.1)
    assert(s.popup.activeInHierarchy, 'the popup must be open')
    s.popup.locking = false
    UI_AnotherGameConfirm.Cancel(s.popup)
    assert(s.again == 1, 'the first settlement opened its confirmation once')
    local agains, confirms = s.again, s.confirmAttempts
    drive(s, 1, 4)
    assert(s.again == agains, 'a cancelled settlement must stay refused')
    assert(s.confirmAttempts == confirms, 'a cancelled settlement must not be confirmed')
    -- The next match ends on the same singleton with its own result.
    s.gameEnd.step = 0
    s.gameEnd.btn_again.activeInHierarchy = true
    s.gameEnd.btn_next.activeInHierarchy = true
    s.gameEnd.game_end_result = { players = { { seat = 1 } } }
    s.gameEnd.transform.gameObject.activeInHierarchy = true
    drive(s, 6, 4)
    assert(s.again == agains + 1, 'the next settlement must be driven after a cancel')
    assert(s.confirmAttempts == confirms + 1, 'the next settlement must be confirmed')
end)

test('a raising settlement show is still observed and keeps its own error', function(s)
    -- The real UI_GameEnd.OnShow stores game_end_result and then walks sub-pages the
    -- previous close unloaded, so it can raise after the result is already on the
    -- page. That raise must not leave the walk blind on the settlement it describes,
    -- and it must keep travelling to the caller unchanged.
    local stored = function(self, callback, args)
        local target = (type(self) == 'table' and self.Inst) or self
        target.game_end_result = args and args.game_end_result
        target.transform.gameObject.activeInHierarchy = true
        error('sub page missing')
    end
    _G.UI_GameEnd.OnShow = stored
    command(s)
    tick(s, 0)
    local ok, err = pcall(function()
        s.gameEnd:OnShow(function() end, { game_end_result = { players = { { seat = 1 } } } })
    end)
    assert(not ok, 'the game error must keep travelling')
    assert(tostring(err):find('sub page missing', 1, true), 'the original error must survive')
    assert(s.R.session ~= nil, 'the raising show must still be observed')
    drive(s, 1, 4)
    assert(s.again >= 1, 'a page whose show raised must still be driven')
end)

test('an answered settlement page is not re-armed while it stays up', function(s)
    -- Confirm latches session.done, but the page (and its game_end_result) stay as
    -- they were until the match view actually closes. attachLive must keep that
    -- answered settlement latched instead of re-observing the same result on every
    -- tick, which would hand the walk a fresh lease over a page it already answered.
    prime(s)
    command(s)
    drive(s, 0, 3)
    assert(s.again == 1 and s.confirmAttempts == 1, 'the first settlement must complete')
    assert(s.R.session and s.R.session.done, 'the confirmation must latch the session')
    local session = s.R.session
    -- The popup is gone (Confirm hid it) but the final page is still showing.
    drive(s, 3, 6)
    assert(s.R.session == session, 'an answered page must keep its own session')
    assert(s.R.session.done, 'an answered page must stay latched')
    assert(s.again == 1, 'an answered page must not be driven again, got ' .. s.again)
    assert(s.confirmAttempts == 1, 'an answered page must not be confirmed again')
end)

test('normal final ranking advances after the game closes its table socket', function(s)
    prime(s); command(s)
    s.gameEnd.btn_again.activeInHierarchy = false
    MJNetMgr.Inst.IsOK = function() return false end
    tick(s, 0.1)
    assert(#s.nextPushed == 1, 'rank confirmation must run without a live table connection')
    assert(s.gameEnd.btn_next.clicks == 0, 'ranking uses its known Lua callback, not an empty UnityEvent')
end)

test('native rematch confirmation works after final table socket closure', function(s)
    prime(s); command(s)
    MJNetMgr.Inst.IsOK = function() return false end
    drive(s, 0, 2)
    assert(s.again == 1 and s.confirmAttempts == 1, 'old table socket is not a matchmaking prerequisite')
end)

test('ranking intro leaves isDoAnimation true after native buttons unlock', function(s)
    prime(s); command(s)
    s.gameEnd.isDoAnimation = true
    s.gameEnd.locking = false
    s.gameEnd.btn_again.activeInHierarchy = false
    MJNetMgr.Inst.IsOK = function() return false end
    tick(s, 0.1)
    assert(#s.nextPushed == 1, 'native unlocked ranking must confirm despite stale animation flag')
end)

print(string.format('%d auto rematch checks passed', count))
