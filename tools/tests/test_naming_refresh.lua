-- Run from the repository root: lua5.1 tools/tests/test_naming_refresh.lua
-- The native bridge, the table UI and the game's seat helper are fakes; this
-- process never talks to a server and never opens a table.
--
-- The fake table page copies one real-game property that matters here: the client
-- paints a seat name from `DesktopMgr.Inst.player_datas[seat].nickname` (the result
-- page literally does
-- `container_name:SetName(DesktopMgr.Inst.player_datas[s.seat].nickname, ...)`)
-- and repaints on `UI_DesktopInfo.Inst:RefreshSeat()`. It never asks
-- `DesktopMgr:get_player_name` for a table label, which is why a module that only
-- wrapped that getter used to be installed and still change nothing on screen.
local asset = "hook-probe/src/main/assets/liqi_config/ui/MajsoulMaxNaming.lua"
local count = 0

local function test(name, body)
    body()
    count = count + 1
    print("ok " .. count .. " - " .. name)
end

-- The Chinese tag carries a U+FEFF byte-order mark after the C, exactly like the
-- game sends it; Lua 5.1 has no \u escape, so the bytes are spelled out.
local CN = "[C" .. string.char(0xEF, 0xBB, 0xBF) .. "N]"

-- The real thresholds of the Rust `zone_tag`, used by the fake native bridge
-- below. The module under test must never reproduce them: it asks for the tag of
-- a seat's account and prepends it verbatim, so a custom label that merely looks
-- like "[JP]..." is never reinterpreted.
local function zoneTag(account)
    local code = math.floor(account / 8388608)
    if code <= 6 then return CN end
    if code <= 12 then return "[JP]" end
    if code <= 15 then return "[EN]" end
    return "[??]"
end

-- One fake table. `payload` is the nickname each seat carries in the response
-- that built the table, i.e. what the server sent plus whatever the Rust modder
-- rewrote it to. `originals` is what the modder published for the current
-- authGame: the raw (pre-prefix) server names it replaced, if any. `wire` is the
-- compact state: "mode\nshow_server\nslot0\nslot1\nslot2\nforce".
local function fixture(options)
    options = options or {}
    _G.__majmax_naming = nil
    local state = { wire = options.wire or "normal\n1\n\n\n\n1", originals = options.originals or {},
        redraws = 0, prefixCalls = 0, getterCalls = 0, payload = options.payload or {},
        rendered = {} }

    __majmax_naming_state = function() return state.wire end
    __majmax_naming_original = function(account)
        local id = tonumber(account)
        return state.originals[id] or ""
    end
    if options.noPrefixBridge then
        __majmax_naming_prefix = nil
    else
        __majmax_naming_prefix = function(account)
            state.prefixCalls = state.prefixCalls + 1
            return zoneTag(tonumber(account))
        end
    end

    -- The game's own getter: a filtered path (taboo names, hidden nicknames,
    -- friend remarks) that the table never uses for a seat label. The fake keeps
    -- the remark branch so "the seat this module did not rename still reports the
    -- game's own answer" can be asserted.
    local class = {}
    function class:get_player_name(seat)
        state.getterCalls = state.getterCalls + 1
        local player = self.player_datas[seat]
        if not player then error("no player data for seat " .. tostring(seat)) end
        local remark = self.remarks[seat]
        if remark and not self.nickname_anonymous then return remark, true end
        return player.nickname, false
    end
    -- The game's own seat helper, including the wrap the minified client uses for
    -- a three-player table and the position 0 it returns for a missing seat.
    function class:seat2LocalPosition(other)
        if self.rule_mode == "Liqi3" then
            local position = self.seat
            for index = 1, 4 do
                if other == position then return index end
                position = position + 1
                if position >= 4 then position = 0 end
            end
            return 0
        end
        return (other - self.seat + 4) % 4 + 1
    end

    local seats = options.seats or { 1, 2, 3, 4 }
    local seat = options.seat or 1
    local datas, remarks = {}, {}
    for index, value in ipairs(seats) do
        datas[value] = { account_id = options.accounts and options.accounts[value] or (1000 + value),
            nickname = state.payload[value] or ("原名" .. value) }
        remarks[value] = options.remarks and options.remarks[value] or false
    end
    local instance = setmetatable({ seat = seat, player_datas = datas, remarks = remarks,
        rule_mode = options.rule_mode or "Liqi4" }, { __index = class })
    DesktopMgr = class
    DesktopMgr.Inst = instance

    -- The table page: every label it draws comes from the seat data, and only a
    -- repaint reads it again. `rendered` is therefore what the player sees.
    local function paint()
        local drawn = {}
        for seatIndex, player in pairs(DesktopMgr.Inst.player_datas) do
            drawn[seatIndex] = player.nickname
        end
        state.rendered = drawn
    end
    if not options.noUi then
        UI_DesktopInfo = { Inst = {
            InitRoom = function(self) paint() end,
            RefreshSeat = function(self, ...)
                state.redraws = state.redraws + 1
                state.redrawArgs = { ... }
                paint()
            end,
        } }
    else
        UI_DesktopInfo = nil
    end

    dofile(asset)
    -- The table was already open before this module ran: the page shows whatever
    -- the authGame response left in the seat data.
    if UI_DesktopInfo then UI_DesktopInfo.Inst:InitRoom() end
    state.tick = __majmax_naming_tick
    -- What the player reads on that page right now.
    state.name = function(seatIndex) return state.rendered[seatIndex] end
    -- What the seat data carries (what the next repaint will use).
    state.data = function(seatIndex) return DesktopMgr.Inst.player_datas[seatIndex].nickname end
    state.getter = function(seatIndex) return DesktopMgr.Inst:get_player_name(seatIndex) end
    state.setWire = function(wire) state.wire = wire end
    state.repaint = function() if UI_DesktopInfo then UI_DesktopInfo.Inst:RefreshSeat() end end
    return state
end

test("normal mode keeps every server name, the friend remark and writes nothing", function()
    local s = fixture({ remarks = { [1] = "好友备注" } })
    s.tick()
    assert(s.name(1) == "原名1")
    -- The untouched getter still answers exactly like the game: the remark and
    -- its flag, because no seat of this table was renamed.
    local name, remark = s.getter(1)
    assert(name == "好友备注" and remark == true)
    for seat = 2, 4 do assert(s.name(seat) == "原名" .. seat) end
    -- The table was repainted once for the first published state.
    assert(s.redraws == 1)
end)

test("preset mode renames the three opponents left, top, right and keeps the own name", function()
    local s = fixture()
    s.tick()
    assert(s.redraws == 1)
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1")
    s.tick()
    -- Seat 1 is the local seat: left = 4 (kamicha), top = 3 (toimen), right = 2.
    assert(s.name(4) == CN .. "领头的羊")
    assert(s.name(3) == CN .. "镇山的虎")
    assert(s.name(2) == CN .. "远见的鹰")
    assert(s.name(1) == "原名1")
    -- The page is up to date because the seat data itself was replaced ...
    assert(s.data(4) == CN .. "领头的羊")
    assert(s.data(1) == "原名1")
    -- ... and the getter agrees for a renamed seat, without the friend remark.
    local _, remark = s.getter(2)
    assert(remark == false)
    -- Exactly one repaint for the change: the table is not reset again.
    assert(s.redraws == 2)
    for _ = 1, 5 do s.tick() end
    assert(s.redraws == 2)
end)

test("a getter-only hook cannot paint the table, the repaint comes from the seat data", function()
    local s = fixture()
    s.tick()
    s.setWire("custom\n1\n甲\n\n")
    local before = s.getterCalls
    s.tick()
    -- The page was repainted without asking the filtered getter even once ...
    assert(s.getterCalls == before)
    -- ... and it shows the label because the seat data carries it. A module that
    -- only wrapped get_player_name would leave this page on the server name.
    assert(s.name(4) == CN .. "甲")
    assert(s.data(4) == CN .. "甲")
end)

test("a repaint is what makes a change visible, a stale page keeps the old name", function()
    local s = fixture()
    s.tick()
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1")
    UI_DesktopInfo = nil -- the page cannot be repainted right now
    assert(pcall(s.tick))
    -- The data is already correct, the page still shows the old name until the
    -- next repaint point (a new round, another choice, or the retried tick).
    UI_DesktopInfo = { Inst = { RefreshSeat = function() end } }
    s.repaint()
    assert(s.data(4) == CN .. "领头的羊")
end)

test("custom names replace only the filled slots and follow the same seats", function()
    local s = fixture()
    s.tick()
    s.setWire("custom\n1\n上家\n\n下家\n1")
    s.tick()
    assert(s.name(4) == CN .. "上家")
    -- Empty slot: the server name of that seat is still there.
    assert(s.name(3) == "原名3")
    assert(s.name(2) == CN .. "下家")
    assert(s.name(1) == "原名1")
    assert(s.redraws == 2)
end)

test("showServer is the sole authority: on, off and on again never doubles the tag", function()
    local s = fixture()
    s.tick()
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1")
    s.tick()
    assert(s.name(4) == CN .. "领头的羊")
    assert(s.name(3) == CN .. "镇山的虎")
    -- Only the switch changes: the labels lose their tag and nothing else.
    s.setWire("abc\n0\n领头的羊\n镇山的虎\n远见的鹰\n1")
    s.tick()
    assert(s.name(4) == "领头的羊")
    assert(s.name(3) == "镇山的虎")
    -- And back: exactly one tag, never two.
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1")
    s.tick()
    assert(s.name(4) == CN .. "领头的羊")
    assert(s.name(3) == CN .. "镇山的虎")
    -- Each switch is one user edit and therefore exactly one repaint.
    assert(s.redraws == 4)
end)

test("a custom label is taken verbatim and never matched against a tag", function()
    local s = fixture()
    s.tick()
    -- The label itself starts with a foreign region tag. The seat's own account
    -- decides the prefix, so nothing is stripped and nothing is inferred.
    s.setWire("custom\n1\n[JP]假前缀\n\n\n1")
    s.tick()
    assert(s.name(4) == CN .. "[JP]假前缀")
    -- With the switch off the label is shown exactly as typed.
    s.setWire("custom\n0\n[JP]假前缀\n\n\n1")
    s.tick()
    assert(s.name(4) == "[JP]假前缀")
    -- Still no cache entry was needed: the label is not a server name.
    assert(s.name(3) == "原名3")
end)

test("the region tag is only asked for while the switch is on, and only once per seat", function()
    local s = fixture({ wire = "abc\n0\n领头的羊\n镇山的虎\n远见的鹰\n1" })
    s.tick()
    assert(s.name(4) == "领头的羊")
    assert(s.prefixCalls == 0)
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1")
    s.tick()
    -- One lookup per opponent, at the moment the data is written (never per frame
    -- and never for the local seat).
    assert(s.prefixCalls == 3)
    assert(s.name(4) == CN .. "领头的羊")
    assert(s.name(3) == CN .. "镇山的虎")
    assert(s.name(2) == CN .. "远见的鹰")
    -- Cached per account: a repaint and the following ticks do not ask again.
    for _ = 1, 10 do s.repaint() end
    for _ = 1, 10 do s.tick() end
    assert(s.prefixCalls == 3)
end)

test("going back to normal restores the names the response replaced", function()
    -- The table was built while preset labels were active, so the response
    -- carries the prefixed labels and the modder published the raw names it
    -- replaced (without a tag: the display layer composes that itself).
    local s = fixture({ wire = "abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1",
        payload = { [2] = CN .. "远见的鹰", [3] = CN .. "镇山的虎", [4] = CN .. "领头的羊" },
        originals = { [1002] = "右家", [1003] = "对家", [1004] = "左家" } })
    s.tick()
    assert(s.name(4) == CN .. "领头的羊")
    assert(s.data(4) == CN .. "领头的羊")

    s.setWire("normal\n1\n\n\n\n1")
    s.tick()
    -- One tag, not two: the cached value is the raw server name.
    assert(s.name(4) == CN .. "左家")
    assert(s.name(3) == CN .. "对家")
    assert(s.name(2) == CN .. "右家")
    -- The own seat was never rewritten, so it never had to be restored.
    assert(s.name(1) == "原名1")
    assert(s.redraws == 2)

    -- Switching the tag off must not leave the old prefix behind ...
    s.setWire("normal\n0\n\n\n\n1")
    s.tick()
    assert(s.name(4) == "左家")
    assert(s.name(3) == "对家")
    -- ... and switching it back on adds exactly one.
    s.setWire("normal\n1\n\n\n\n1")
    s.tick()
    assert(s.name(4) == CN .. "左家")

    -- A custom choice after that still works on the restored names.
    s.setWire("custom\n1\n甲\n\n\n1")
    s.tick()
    assert(s.name(4) == CN .. "甲" and s.name(3) == CN .. "对家" and s.name(2) == CN .. "右家")
end)

test("normal mode drops the stored tag of a seat the live switch turns off", function()
    -- The table was built with the switch on, so every opponent name carries the
    -- tag. Nothing was renamed, so the modder has no cached name: the only way
    -- the switch can act on this table is to remove exactly that tag again.
    local s = fixture({ payload = { [2] = CN .. "右家", [3] = CN .. "对家", [4] = CN .. "左家" },
        wire = "normal\n0\n\n\n\n1" })
    s.tick()
    assert(s.name(4) == "左家")
    assert(s.name(3) == "对家")
    assert(s.name(2) == "右家")
    -- Back on, the tag the response was built with comes back exactly once.
    s.setWire("normal\n1\n\n\n\n1")
    s.tick()
    assert(s.name(4) == CN .. "左家")
    assert(s.name(3) == CN .. "对家")
end)

test("only the seat's own tag is ever stripped, and never a lookalike", function()
    local jp = 7 * 8388608
    local s = fixture({ accounts = { [2] = jp + 1, [3] = 2202, [4] = jp + 2 },
        payload = { [2] = "[JP]右家", [3] = "[JP]对家", [4] = "[JP]左家" },
        wire = "normal\n0\n\n\n\n1" })
    s.tick()
    -- The Japanese seats own the [JP] tag, so it is theirs to drop ...
    assert(s.name(4) == "左家")
    assert(s.name(2) == "右家")
    -- ... while the Chinese seat never had it: a [JP] text on a [CN] account is
    -- part of the name and is left alone.
    assert(s.name(3) == "[JP]对家")
end)

test("a seat this module did not rename keeps the game's own getter answer", function()
    -- Switch off and an unprefixed name: nothing to strip, so the game's own
    -- answer (and its remark flag) must come through untouched.
    local s = fixture({ remarks = { [2] = "好友备注" }, wire = "normal\n0\n\n\n\n1" })
    s.tick()
    local name, remark = s.getter(2)
    assert(name == "好友备注" and remark == true)
    assert(s.name(2) == "原名2")
end)

test("a seat keeps its own region in both the label and the restore path", function()
    local jp = 7 * 8388608
    local s = fixture({ wire = "abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1",
        accounts = { [2] = jp + 1, [3] = 2202, [4] = jp + 2 },
        originals = { [jp + 2] = "左家" } })
    s.tick()
    -- Same labels, different regions: the tag is a property of the seat.
    assert(s.name(4) == "[JP]领头的羊")
    assert(s.name(3) == CN .. "镇山的虎")
    assert(s.name(2) == "[JP]远见的鹰")
    s.setWire("normal\n1\n\n\n\n1")
    s.tick()
    assert(s.name(4) == "[JP]左家")
    s.setWire("normal\n0\n\n\n\n1")
    s.tick()
    assert(s.name(4) == "左家")
end)

test("a missing prefix bridge shows the label without a tag instead of failing", function()
    local s = fixture({ noPrefixBridge = true, wire = "abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1" })
    s.tick()
    assert(s.name(4) == "领头的羊")
    assert(s.name(1) == "原名1")
end)

test("a state in an older shape is ignored as a whole", function()
    -- Four lines (the previous build's format): the display layer decides
    -- nothing rather than guessing which line is the switch.
    local s = fixture({ wire = "abc\nA\nB\nC" })
    s.tick()
    assert(s.name(4) == "原名4")
    assert(s.name(1) == "原名1")
    assert(s.data(4) == "原名4")
    -- And the module keeps working once a state in the current shape arrives.
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1")
    s.tick()
    assert(s.name(4) == CN .. "领头的羊")
end)

test("a five line state is accepted as a forced change", function()
    local s = fixture()
    s.tick()
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰")
    s.tick()
    assert(s.name(4) == CN .. "领头的羊" and s.redraws == 2)
end)

test("a missing table UI is retried a bounded number of times and never raises", function()
    local s = fixture({ noUi = true })
    s.tick()
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1")
    for _ = 1, 60 do s.tick() end
    -- The seat data is correct even while the page is missing.
    assert(s.data(2) == CN .. "远见的鹰" and s.data(1) == "原名1")
    local redraws, calls = 0, 0
    UI_DesktopInfo = { Inst = { RefreshSeat = function() redraws = redraws + 1; calls = calls + 1 end } }
    -- The retry budget is gone: no delayed redraw of a table that was never open.
    s.tick()
    assert(calls == 0)
    -- A new choice starts a new, bounded attempt.
    s.setWire("custom\n1\nX\n\n\n1")
    s.tick()
    assert(calls == 1 and redraws == 1)
    assert(s.data(4) == CN .. "X")
end)

test("a failing or unknown UI redraw is swallowed", function()
    local s = fixture()
    UI_DesktopInfo.Inst.RefreshSeat = function() error("scene not ready") end
    s.tick()
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1")
    for _ = 1, 40 do s.tick() end
    assert(s.data(4) == CN .. "领头的羊" and s.data(1) == "原名1")
    -- A page that is not loaded at all is equally harmless.
    UI_DesktopInfo = nil
    s.setWire("custom\n1\n甲\n乙\n丙\n1")
    for _ = 1, 40 do s.tick() end
    assert(s.data(2) == CN .. "丙")
end)

test("a three-player table renames its two opponents left to right", function()
    local s = fixture({ seats = { 1, 2, 3 }, rule_mode = "Liqi3" })
    s.tick()
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1")
    s.tick()
    -- Only two opponents exist: kamicha (seat 3) on the left, shimocha (seat 2)
    -- on the right. The third slot stays unused instead of guessing a place.
    assert(s.name(3) == CN .. "领头的羊")
    assert(s.name(2) == CN .. "镇山的虎")
    assert(s.name(1) == "原名1")
    assert(s.redraws == 2)

    -- A custom slot two is not used by a three-player table.
    s.setWire("custom\n1\n甲\n乙\n丙\n1")
    s.tick()
    assert(s.name(3) == CN .. "甲" and s.name(2) == CN .. "乙")
end)

test("a three-player table whose local seat is not the first still maps correctly", function()
    -- Sanma seats stay inside 1..3: the game's own helper wraps a phantom seat 0,
    -- so positions are 1 (self), then the two remaining seats.
    local s = fixture({ seats = { 1, 2, 3 }, seat = 2, rule_mode = "Liqi3" })
    s.tick()
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1")
    s.tick()
    -- Turn order 1 -> 2 -> 3: kamicha is seat 1 (left), shimocha is seat 3 (right).
    assert(s.name(1) == CN .. "领头的羊")
    assert(s.name(3) == CN .. "镇山的虎")
    assert(s.name(2) == "原名2")
end)

test("a robot seat is renamed but has nothing to restore", function()
    -- A robot has no account id, so it has no region: the label stays bare even
    -- though the switch is on.
    local s = fixture({ accounts = { [2] = 0 }, payload = { [2] = CN .. "远见的鹰" },
        originals = { [1004] = "左家" } })
    s.tick()
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1")
    s.tick()
    assert(s.name(2) == "远见的鹰")
    -- Back to normal there is no cached name for the robot (account 0), so the
    -- value the table already carried stands instead of raising.
    s.setWire("normal\n1\n\n\n\n1")
    s.tick()
    assert(s.name(4) == CN .. "左家")
    assert(s.name(2) == CN .. "远见的鹰")
    -- A seat that has no data at all still raises exactly like the game does.
    local ok = pcall(function() return DesktopMgr.Inst:get_player_name(9) end)
    assert(ok == false)
end)

test("an untouched game state falls through and writes nothing", function()
    local s = fixture()
    -- No state published yet: the module must not decide anything.
    __majmax_naming_state = nil
    s.tick()
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1")
    s.tick()
    assert(s.data(4) == "原名4")
    -- Nor does a table without the seat helper.
    _G.__majmax_naming = nil
    DesktopMgr.Inst.seat2LocalPosition = nil
    dofile(asset)
    __majmax_naming_tick()
    assert(s.data(4) == "原名4" and s.data(1) == "原名1")
end)

test("an incomplete table shape is ignored instead of raising", function()
    _G.__majmax_naming = nil
    __majmax_naming_state = function() return "abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1" end
    __majmax_naming_original = function() return "" end
    __majmax_naming_prefix = function() return CN end
    DesktopMgr = { get_player_name = function(self, seat) return "原名", false end }
    DesktopMgr.Inst = { player_datas = {} }
    UI_DesktopInfo = nil
    dofile(asset)
    __majmax_naming_tick()
    -- Missing player data means no replacement, not an error.
    assert(DesktopMgr.get_player_name(DesktopMgr.Inst, 1) == "原名")
    assert(DesktopMgr.Inst.player_datas[1] == nil)
end)

test("a name the game rebuilds is put back on the next tick", function()
    local s = fixture()
    s.tick()
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1")
    s.tick()
    assert(s.name(4) == CN .. "领头的羊")
    assert(s.redraws == 2)
    -- A new round restores the seat names from the response copy
    -- (`DesktopMgr.player_datas_temp`): the next tick must heal it instead of
    -- leaving the table on the server name.
    DesktopMgr.Inst.player_datas[4].nickname = "原名4"
    s.tick()
    assert(s.data(4) == CN .. "领头的羊")
    assert(s.name(4) == CN .. "领头的羊")
    assert(s.redraws == 3)
end)

test("a class table the game rebuilt is wrapped again on the next tick", function()
    local s = fixture()
    s.tick()
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1")
    s.tick()
    assert(s.name(4) == CN .. "领头的羊")
    -- A reloaded scene replaces the class table, taking the wrapper with it: the
    -- next tick must notice, install again, and keep the table data intact.
    local instance = DesktopMgr.Inst
    local fresh = {}
    fresh.get_player_name = function(self, seat)
        return self.player_datas[seat].nickname, false
    end
    fresh.seat2LocalPosition = function(self, other) return (other - self.seat + 4) % 4 + 1 end
    local plain = fresh.get_player_name
    setmetatable(instance, { __index = fresh })
    DesktopMgr = fresh
    DesktopMgr.Inst = instance
    s.tick()
    -- The wrapper is installed on the rebuilt class, on top of its own getter.
    assert(rawget(DesktopMgr, "get_player_name") ~= plain)
    assert(s.data(4) == CN .. "领头的羊" and s.data(1) == "原名1")
end)

test("one repaint per choice, resolved through the inherited UI instance method", function()
    local s = fixture()
    local calls, args = 0, nil
    local class = { RefreshSeat = function(self, ...) calls = calls + 1; args = { ... } end }
    UI_DesktopInfo = { Inst = setmetatable({}, { __index = class }) }
    s.tick()
    assert(calls == 1)
    -- The flag is the one the client passes when the seat content of a live table
    -- changes (DesktopMgr:ChangeMainBody / RefreshSeatOnNewRound), not the no-arg
    -- form it uses while initialising a room.
    assert(args[1] == true and #args == 1)
    s.tick()
    assert(calls == 1)
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1")
    s.tick()
    assert(calls == 2)
    for _ = 1, 40 do s.tick() end
    assert(calls == 2)
end)

test("emptying a custom slot puts that seat's server name back", function()
    local s = fixture()
    s.tick()
    s.setWire("custom\n1\n上家\n对家\n下家\n1")
    s.tick()
    assert(s.name(4) == CN .. "上家" and s.name(3) == CN .. "对家" and s.name(2) == CN .. "下家")
    -- The user clears the middle field: only that seat goes back to the name the
    -- response carried, the other two keep their labels.
    s.setWire("custom\n1\n上家\n\n下家\n1")
    s.tick()
    assert(s.name(3) == "原名3")
    assert(s.data(3) == "原名3")
    assert(s.name(4) == CN .. "上家" and s.name(2) == CN .. "下家")
end)

test("a seat that stops being an opponent gets its own name back", function()
    local s = fixture()
    s.tick()
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1")
    s.tick()
    assert(s.name(4) == CN .. "领头的羊")
    -- `DesktopMgr:ChangeMainBody` (replay / observer) makes another seat the main
    -- body: that seat is no longer an opponent and must never show a label.
    DesktopMgr.Inst.seat = 4
    s.tick()
    assert(s.data(4) == "原名4")
    -- The three remaining seats are now left, top, right of that main body.
    assert(s.name(3) == CN .. "领头的羊")
    assert(s.name(2) == CN .. "镇山的虎")
    assert(s.name(1) == CN .. "远见的鹰")
end)

test("normal mode never applies a label, even when the state carries one", function()
    -- `normal` publishes empty slots, but a half updated install could hand over a
    -- normal mode with labels still in it: a normal mode must never rename.
    local s = fixture({ wire = "normal\n1\n领头的羊\n镇山的虎\n远见的鹰\n1" })
    s.tick()
    assert(s.name(4) == "原名4" and s.name(3) == "原名3" and s.name(2) == "原名2")
    assert(s.data(4) == "原名4")
    -- And an unknown mode spelling fails closed the same way.
    s.setWire("random\n1\n甲\n乙\n丙\n1")
    s.tick()
    assert(s.data(4) == "原名4")
    -- The two label modes are the only ones that rename.
    s.setWire("custom\n1\n甲\n乙\n丙\n1")
    s.tick()
    assert(s.data(4) == CN .. "甲")
    s.setWire("anonymize\n1\n甲\n乙\n丙\n1")
    s.tick()
    assert(s.data(4) == CN .. "甲")
end)

test("a mode cycle never pollutes the remembered server name", function()
    local s = fixture()
    s.tick()
    local expected = { "原名4", "原名3", "原名2" }
    local function assertServerNames()
        local seats = { 4, 3, 2 }
        for index = 1, 3 do
            assert(s.name(seats[index]) == expected[index], s.name(seats[index]))
            assert(s.data(seats[index]) == expected[index], s.data(seats[index]))
        end
    end
    -- normal -> preset -> custom -> normal -> preset(with the switch off) -> normal
    for round = 1, 3 do
        s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰\n1")
        s.tick()
        assert(s.name(4) == CN .. "领头的羊")
        s.setWire("custom\n1\n甲\n乙\n丙\n1")
        s.tick()
        assert(s.name(4) == CN .. "甲")
        s.setWire("normal\n1\n\n\n\n1")
        s.tick()
        -- The true server name every time, never a label from an earlier round of
        -- the cycle and never a doubled tag.
        assertServerNames()
        s.setWire("abc\n0\n领头的羊\n镇山的虎\n远见的鹰\n1")
        s.tick()
        assert(s.name(4) == "领头的羊")
        s.setWire("normal\n0\n\n\n\n1")
        s.tick()
        assertServerNames()
    end
    -- And the last choice still works on the same records.
    s.setWire("custom\n1\n上家\n\n\n1")
    s.tick()
    assert(s.name(4) == CN .. "上家" and s.name(3) == "原名3" and s.name(2) == "原名2")
end)

print("passed " .. count .. " opponent-naming refresh tests")
