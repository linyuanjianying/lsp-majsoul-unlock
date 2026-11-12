-- Run from the repository root: lua5.1 tools/tests/test_naming_refresh.lua
-- The native bridge, the table UI and the game's seat helper are fakes; this
-- process never talks to a server and never opens a table.
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
-- compact state: "mode\nshow_server\nslot0\nslot1\nslot2".
local function fixture(options)
    options = options or {}
    _G.__majmax_naming = nil
    local state = { wire = options.wire or "normal\n1\n\n\n", originals = options.originals or {},
        redraws = 0, payload = options.payload or {}, prefixCalls = 0 }

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

    -- The game's own seat helper, including the wrap the minified client uses for
    -- a three-player table and the position 0 it returns for a missing seat.
    local class = {}
    function class:get_player_name(seat)
        local player = self.player_datas[seat]
        return player.nickname, self.remarks[seat] or false
    end
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

    if not options.noUi then
        UI_DesktopInfo = { Inst = { RefreshSeat = function(self, animated)
            assert(animated == true)
            state.redraws = state.redraws + 1
        end } }
    else
        UI_DesktopInfo = nil
    end

    dofile(asset)
    state.tick = __majmax_naming_tick
    state.name = function(seatIndex)
        return DesktopMgr.Inst:get_player_name(seatIndex)
    end
    state.setWire = function(wire) state.wire = wire end
    return state
end

test("normal mode keeps every server name and the friend remark", function()
    local s = fixture({ remarks = { [1] = true } })
    s.tick()
    assert(s.name(1) == "原名1")
    local name, remark = s.name(1)
    assert(name == "原名1" and remark == true)
    for seat = 2, 4 do assert(s.name(seat) == "原名" .. seat) end
    -- Installing the hook already redrew once; the game names are unchanged.
    assert(s.redraws == 1)
end)

test("preset mode renames the three opponents left, top, right and keeps the own name", function()
    local s = fixture()
    s.tick()
    assert(s.redraws == 1)
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰")
    s.tick()
    -- Seat 1 is the local seat: left = 4 (kamicha), top = 3 (toimen), right = 2.
    assert(s.name(4) == CN .. "领头的羊")
    assert(s.name(3) == CN .. "镇山的虎")
    assert(s.name(2) == CN .. "远见的鹰")
    assert(s.name(1) == "原名1")
    -- The label is not a friend remark.
    local _, remark = s.name(2)
    assert(remark == false)
    -- Exactly one redraw for the change: the table is not reset again.
    assert(s.redraws == 2)
    for _ = 1, 5 do s.tick() end
    assert(s.redraws == 2)
end)

test("custom names replace only the filled slots and follow the same seats", function()
    local s = fixture()
    s.tick()
    s.setWire("custom\n1\n上家\n\n下家")
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
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰")
    s.tick()
    assert(s.name(4) == CN .. "领头的羊")
    assert(s.name(3) == CN .. "镇山的虎")
    -- Only the switch changes: the labels lose their tag and nothing else.
    s.setWire("abc\n0\n领头的羊\n镇山的虎\n远见的鹰")
    s.tick()
    assert(s.name(4) == "领头的羊")
    assert(s.name(3) == "镇山的虎")
    -- And back: exactly one tag, never two.
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰")
    s.tick()
    assert(s.name(4) == CN .. "领头的羊")
    assert(s.name(3) == CN .. "镇山的虎")
    -- Each switch is one user edit and therefore exactly one redraw.
    assert(s.redraws == 4)
end)

test("a custom label is taken verbatim and never matched against a tag", function()
    local s = fixture()
    s.tick()
    -- The label itself starts with a foreign region tag. The seat's own account
    -- decides the prefix, so nothing is stripped and nothing is inferred.
    s.setWire("custom\n1\n[JP]假前缀\n\n")
    s.tick()
    assert(s.name(4) == CN .. "[JP]假前缀")
    -- With the switch off the label is shown exactly as typed.
    s.setWire("custom\n0\n[JP]假前缀\n\n")
    s.tick()
    assert(s.name(4) == "[JP]假前缀")
    -- Still no cache entry was needed: the label is not a server name.
    assert(s.name(3) == "原名3")
end)

test("the region tag is only asked for while the switch is on, and only once per seat", function()
    local s = fixture({ wire = "abc\n0\n领头的羊\n镇山的虎\n远见的鹰" })
    s.tick()
    assert(s.name(4) == "领头的羊")
    assert(s.prefixCalls == 0)
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰")
    s.tick()
    assert(s.name(4) == CN .. "领头的羊")
    assert(s.prefixCalls == 1)
    assert(s.name(3) == CN .. "镇山的虎")
    assert(s.name(2) == CN .. "远见的鹰")
    assert(s.prefixCalls == 3)
    -- Cached per account: a redraw does not ask again.
    for _ = 1, 10 do
        assert(s.name(4) == CN .. "领头的羊")
    end
    assert(s.prefixCalls == 3)
end)

test("going back to normal restores the names the response replaced", function()
    -- The table was built while preset labels were active, so the response
    -- carries the prefixed labels and the modder published the raw names it
    -- replaced (without a tag: the display layer composes that itself).
    local s = fixture({ wire = "abc\n1\n领头的羊\n镇山的虎\n远见的鹰",
        payload = { [2] = CN .. "远见的鹰", [3] = CN .. "镇山的虎", [4] = CN .. "领头的羊" },
        originals = { [1002] = "右家", [1003] = "对家", [1004] = "左家" } })
    s.tick()
    assert(s.name(4) == CN .. "领头的羊")

    s.setWire("normal\n1\n\n\n")
    s.tick()
    -- One tag, not two: the cached value is the raw server name.
    assert(s.name(4) == CN .. "左家")
    assert(s.name(3) == CN .. "对家")
    assert(s.name(2) == CN .. "右家")
    -- The own seat was never rewritten, so it never had to be restored.
    assert(s.name(1) == "原名1")
    assert(s.redraws == 2)

    -- Switching the tag off must not leave the old prefix behind ...
    s.setWire("normal\n0\n\n\n")
    s.tick()
    assert(s.name(4) == "左家")
    assert(s.name(3) == "对家")
    -- ... and switching it back on adds exactly one.
    s.setWire("normal\n1\n\n\n")
    s.tick()
    assert(s.name(4) == CN .. "左家")

    -- A custom choice after that still works on the restored names.
    s.setWire("custom\n1\n甲\n\n")
    s.tick()
    assert(s.name(4) == CN .. "甲" and s.name(3) == CN .. "对家" and s.name(2) == CN .. "右家")
end)

test("normal mode drops the stored tag of a seat the live switch turns off", function()
    -- The table was built with the switch on, so every opponent name carries the
    -- tag. Nothing was renamed, so there is no cached name: the only way the
    -- switch can act on this table is to remove exactly that tag again.
    local s = fixture({ payload = { [2] = CN .. "右家", [3] = CN .. "对家", [4] = CN .. "左家" },
        wire = "normal\n0\n\n\n" })
    s.tick()
    assert(s.name(4) == "左家")
    assert(s.name(3) == "对家")
    assert(s.name(2) == "右家")
    -- Back on, the game's own (prefixed) answer stands, with one tag only.
    s.setWire("normal\n1\n\n\n")
    s.tick()
    assert(s.name(4) == CN .. "左家")
    assert(s.name(3) == CN .. "对家")
end)

test("only the seat's own tag is ever stripped, and never a lookalike", function()
    local jp = 7 * 8388608
    local s = fixture({ accounts = { [2] = jp + 1, [3] = 2202, [4] = jp + 2 },
        payload = { [2] = "[JP]右家", [3] = "[JP]对家", [4] = "[JP]左家" },
        wire = "normal\n0\n\n\n" })
    s.tick()
    -- The Japanese seats own the [JP] tag, so it is theirs to drop ...
    assert(s.name(4) == "左家")
    assert(s.name(2) == "右家")
    -- ... while the Chinese seat never had it: a [JP] text on a [CN] account is
    -- part of the name and is left alone.
    assert(s.name(3) == "[JP]对家")
end)

test("a seat whose rendered name does not change keeps the friend remark", function()
    -- Switch off and an unprefixed name: nothing to strip, so the game's own
    -- answer (and its remark flag) must come through untouched.
    local s = fixture({ remarks = { [2] = true }, wire = "normal\n0\n\n\n" })
    s.tick()
    local name, remark = s.name(2)
    assert(name == "原名2" and remark == true)
end)

test("a seat keeps its own region in both the label and the restore path", function()
    local jp = 7 * 8388608
    local s = fixture({ wire = "abc\n1\n领头的羊\n镇山的虎\n远见的鹰",
        accounts = { [2] = jp + 1, [3] = 2202, [4] = jp + 2 },
        originals = { [jp + 2] = "左家" } })
    s.tick()
    -- Same labels, different regions: the tag is a property of the seat.
    assert(s.name(4) == "[JP]领头的羊")
    assert(s.name(3) == CN .. "镇山的虎")
    assert(s.name(2) == "[JP]远见的鹰")
    s.setWire("normal\n1\n\n\n")
    s.tick()
    assert(s.name(4) == "[JP]左家")
    s.setWire("normal\n0\n\n\n")
    s.tick()
    assert(s.name(4) == "左家")
end)

test("a missing prefix bridge shows the label without a tag instead of failing", function()
    local s = fixture({ noPrefixBridge = true, wire = "abc\n1\n领头的羊\n镇山的虎\n远见的鹰" })
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
    -- And the module keeps working once a state in the current shape arrives.
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰")
    s.tick()
    assert(s.name(4) == CN .. "领头的羊")
end)

test("a missing table UI is retried a bounded number of times and never raises", function()
    local s = fixture({ noUi = true })
    s.tick()
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰")
    for _ = 1, 60 do s.tick() end
    -- The names still work while the UI is missing.
    assert(s.name(2) == CN .. "远见的鹰" and s.name(1) == "原名1")
    UI_DesktopInfo = { Inst = { RefreshSeat = function() end } }
    -- The retry budget is gone: no delayed redraw of a table that was never open.
    s.tick()
    -- A new choice starts a new, bounded attempt.
    s.setWire("custom\n1\nX\n\n")
    s.tick()
    assert(s.name(4) == CN .. "X")
end)

test("a failing or unknown UI redraw is swallowed", function()
    local s = fixture()
    UI_DesktopInfo.Inst.RefreshSeat = function() error("scene not ready") end
    s.tick()
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰")
    for _ = 1, 40 do s.tick() end
    assert(s.name(4) == CN .. "领头的羊" and s.name(1) == "原名1")
    -- A page that is not loaded at all is equally harmless.
    UI_DesktopInfo = nil
    s.setWire("custom\n1\n甲\n乙\n丙")
    for _ = 1, 40 do s.tick() end
    assert(s.name(2) == CN .. "丙")
end)

test("a three-player table renames its two opponents left to right", function()
    local s = fixture({ seats = { 1, 2, 3 }, rule_mode = "Liqi3" })
    s.tick()
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰")
    s.tick()
    -- Only two opponents exist: kamicha (seat 3) on the left, shimocha (seat 2)
    -- on the right. The third slot stays unused instead of guessing a place.
    assert(s.name(3) == CN .. "领头的羊")
    assert(s.name(2) == CN .. "镇山的虎")
    assert(s.name(1) == "原名1")
    assert(s.redraws == 2)

    -- A custom slot two is not used by a three-player table.
    s.setWire("custom\n1\n甲\n乙\n丙")
    s.tick()
    assert(s.name(3) == CN .. "甲" and s.name(2) == CN .. "乙")
end)

test("a three-player table whose local seat is not the first still maps correctly", function()
    -- Sanma seats stay inside 1..3: the game's own helper wraps a phantom seat 0,
    -- so positions are 1 (self), then the two remaining seats.
    local s = fixture({ seats = { 1, 2, 3 }, seat = 2, rule_mode = "Liqi3" })
    s.tick()
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰")
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
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰")
    s.tick()
    assert(s.name(2) == "远见的鹰")
    -- Back to normal there is no cached name for the robot (account 0), so the
    -- payload value stands instead of raising.
    s.setWire("normal\n1\n\n\n")
    s.tick()
    assert(s.name(4) == CN .. "左家")
    assert(s.name(2) == CN .. "远见的鹰")
    -- The game's getter for a seat that has no data is unchanged, error included.
    local ok = pcall(function() return DesktopMgr.Inst:get_player_name(9) end)
    assert(ok == false)
end)

test("an untouched game state falls through to the original getter", function()
    local s = fixture()
    -- No state published yet: the module must not decide anything.
    __majmax_naming_state = nil
    s.tick()
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰")
    s.tick()
    assert(s.name(4) == "原名4")
    -- Nor does a table without the seat helper.
    _G.__majmax_naming = nil
    DesktopMgr.Inst.seat2LocalPosition = nil
    dofile(asset)
    __majmax_naming_tick()
    assert(s.name(4) == "原名4" and s.name(1) == "原名1")
end)

test("an incomplete table shape is ignored instead of raising", function()
    _G.__majmax_naming = nil
    __majmax_naming_state = function() return "abc\n1\n领头的羊\n镇山的虎\n远见的鹰" end
    __majmax_naming_original = function() return "" end
    __majmax_naming_prefix = function() return CN end
    DesktopMgr = { get_player_name = function(self, seat) return "原名", false end }
    DesktopMgr.Inst = { player_datas = {} }
    UI_DesktopInfo = nil
    dofile(asset)
    __majmax_naming_tick()
    assert(DesktopMgr.Inst.get_player_name == nil)
    assert(DesktopMgr.get_player_name ~= nil)
    -- Missing player data means no replacement, not an error.
    assert(DesktopMgr.get_player_name(DesktopMgr.Inst, 1) == "原名")
end)

test("a class table the game rebuilt is hooked again on the next tick", function()
    local s = fixture()
    s.tick()
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰")
    s.tick()
    assert(s.name(4) == CN .. "领头的羊")
    -- A reloaded scene replaces the class table, taking the wrapper with it: the
    -- next tick must notice and install again instead of going silent.
    local instance = DesktopMgr.Inst
    local fresh = {}
    function fresh:get_player_name(seat)
        return self.player_datas[seat].nickname, false
    end
    function fresh:seat2LocalPosition(other)
        return (other - self.seat + 4) % 4 + 1
    end
    setmetatable(instance, { __index = fresh })
    DesktopMgr = fresh
    DesktopMgr.Inst = instance
    s.tick()
    assert(s.name(4) == CN .. "领头的羊" and s.name(1) == "原名1")
end)

test("redraw resolves the inherited UI instance method exactly once per choice", function()
    local s = fixture()
    local calls = 0
    local class = { RefreshSeat = function(self, animated)
        assert(animated == true)
        calls = calls + 1
    end }
    UI_DesktopInfo = { Inst = setmetatable({}, { __index = class }) }
    s.tick()
    assert(calls == 1)
    s.tick()
    assert(calls == 1)
    s.setWire("abc\n1\n领头的羊\n镇山的虎\n远见的鹰")
    s.tick()
    assert(calls == 2)
    for _ = 1, 40 do s.tick() end
    assert(calls == 2)
end)

print("passed " .. count .. " opponent-naming refresh tests")
