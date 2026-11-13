-- Opponent names for the table that is on screen right now.
--
-- The Rust modder rewrites the names inside the `authGame` response, which means
-- a change can only be seen in the next game. This module closes that gap on the
-- display side, and it renames the table exactly the way the client renames it
-- itself (`DesktopMgr:SetNicknameAnonymous`): the opponent seats of the current
-- table get their `player_datas[seat].nickname` replaced, and the client's own
-- seat redraw is called once when that changed something.
--
-- That field is the one the client paints with: its result page builds every card
-- with `container_name:SetName(DesktopMgr.Inst.player_datas[seat].nickname,
-- ...)` (UI_GameEnd), and its anonymous mode renames a live table by writing this
-- very field and then calling `UI_DesktopInfo.Inst:InitRoom()` + `RefreshSeat()`.
-- `DesktopMgr:get_player_name` is a different, filtered path (taboo check, hidden
-- nicknames, friend remarks) and no table label is built through it, which is why
-- a previous version of this file -- one that only wrapped that getter -- could be
-- installed correctly and still never change a single name on screen.
--
-- Redraw: `UI_DesktopInfo.Inst:RefreshSeat(true)`, i.e. the call the client makes
-- itself every time the seat content changes while the table is up
-- (`DesktopMgr:ChangeMainBody` and `DesktopMgr:RefreshSeatOnNewRound` both set
-- `self.seat` and then repaint the seats, and the names have to follow the seat,
-- so that repaint covers them). `InitRoom()` -- also part of the client's own
-- rename sequence -- is the room-initialisation call and is *not* used here: it
-- runs once per table, and re-running it in the middle of a round would reset the
-- round UI. Known uncertainty: the client's `UI_DesktopInfo` chunk is not part of
-- the available decompiled dump (the packaged scripts are encrypted), so this
-- cannot be read line by line. Should a seat label turn out not to be repainted by
-- `RefreshSeat`, the replacement still lands on the next repaint point (the next
-- round, the next seat switch, the result page or the next `authGame`), because
-- the data field above is the client's only source for a name.
--
-- The getter is still wrapped, but only so that a consumer of it reports the same
-- name the table shows, and only for the seats this module actually renamed. A
-- seat that was not renamed keeps the game's own answer, remark flag included.
--
-- It never touches the network, the protocol or the server payload, it runs only
-- on Unity's main thread from the native pump, and it only ever replaces the names
-- of the opponents of the current table: the local seat keeps its own name and
-- every failure falls back to the untouched game data.
--
-- A replaced name is composed as `region tag + label`, where the tag comes from
-- the seat's account id (asked from the native side, the same zone_tag the
-- authGame rewrite uses). Whether it is added at all is decided by the
-- independent showServer switch alone - for preset labels, custom labels and
-- restored server names alike - so toggling it never leaves a stale prefix
-- behind nor produces a doubled one.

local N = rawget(_G, '__majmax_naming') or {}
_G.__majmax_naming = N

-- A redraw is retried only while the table UI is not there yet: 30 ticks of the
-- native pump (100 ms) bound a temporary scene change, and the first success
-- ends the retry. A table that is not open at all is left to the next authGame.
local REFRESH_ATTEMPTS = 30

-- ToLua resolves a require'd global lazily, so reading a page that is not loaded
-- raises instead of returning nil. Everything here goes through rawget.
local function g(name)
    return rawget(_G, name)
end

-- The state published by the modder: "mode\nshow_server\nslot0\nslot1\nslot2\nforce_refresh",
-- where the second line is "1" when the independent showServer switch is on and
-- each slot is the label that replaces the server name, or empty meaning "keep
-- it". The raw string doubles as the change token: a different value is exactly
-- one user edit, and flipping only the switch is one too.
--
-- A state in any other shape is ignored as a whole: the native side and this
-- file ship in the same build, and "no rename" is the safe answer to a half
-- updated install (names then stay exactly as the game renders them).
local function read_state()
    local call = rawget(_G, '__majmax_naming_state')
    if type(call) ~= 'function' then return nil end
    local ok, raw = pcall(call)
    if not ok or type(raw) ~= 'string' or raw == '' then return nil end
    local mode, show, first, second, third, force =
        raw:match('^([^\n]*)\n([^\n]*)\n([^\n]*)\n([^\n]*)\n([^\n]*)\n([^\n]*)$')
    if not mode then
        mode, show, first, second, third = raw:match('^([^\n]*)\n([^\n]*)\n([^\n]*)\n([^\n]*)\n([^\n]*)$')
        force = '1'
    end
    if not mode then return nil end
    -- Only the two label modes ever use a slot. `normal` publishes empty slots
    -- (the modder's `opponent_label` answers nothing for it), but this module must
    -- not depend on that alone: a half updated install -- a new native side with
    -- an old state, or the other way round -- could hand over a normal mode with
    -- labels still in it, and a normal mode must never rename anything. An
    -- unknown spelling fails closed the same way: the game's own names stay.
    local labels_mode = mode == 'abc' or mode == 'custom' or mode == 'anonymize'
    return {
        signature = raw,
        mode = mode,
        labels_mode = labels_mode,
        show = show == '1',
        force = force == '1',
        labels = { first, second, third },
    }
end

-- Region tag of one seat ("[CN]", "[JP]", "[EN]", "[??]" or nothing).
--
-- The tag belongs to the account, not to the text on screen: a custom label may
-- itself start with "[JP]", and a preset label carries no hint at all. So the
-- value is asked from the modder - the very same zone_tag the authGame rewrite
-- uses - and never derived from a string here. It is only fetched when the
-- independent showServer switch is on, and cached per account because a tag
-- cannot change within a process (at most one entry per seat ever seen).
local function prefix(account, show)
    if not show or account <= 0 then return '' end
    local cache = N.prefix
    if not cache then
        cache = {}
        N.prefix = cache
        N.prefixCount = 0
    end
    local cached = cache[account]
    if cached then return cached end
    local call = rawget(_G, '__majmax_naming_prefix')
    -- No bridge at all: show the label without a tag instead of refusing to
    -- rename the seat. A failed call is not cached, a tag is.
    if type(call) ~= 'function' then return '' end
    local ok, value = pcall(call, string.format('%.0f', account))
    if not ok or type(value) ~= 'string' then return '' end
    if value ~= '' then
        -- Bounded: a long session can meet many accounts, and a tag is only
        -- worth a lookup while that seat is on screen.
        if N.prefixCount >= 128 then
            cache = {}
            N.prefix = cache
            N.prefixCount = 0
        end
        cache[account] = value
        N.prefixCount = N.prefixCount + 1
    end
    return value
end

-- The table that is on screen right now, or nil.
local function table_now()
    local desktop = g('DesktopMgr')
    local instance = desktop and rawget(desktop, 'Inst')
    if type(instance) ~= 'table' then return nil end
    return instance
end

-- Opponent seats in screen order, derived from the game's own turn order:
-- local position 1 is the local seat and the positions grow towards the player
-- who plays before the local one. Ordering the opponents by descending local
-- position therefore gives [kamicha (left), toimen (top), shimocha (right)] on a
-- four-player table and [kamicha (left), shimocha (right)] on a three-player one,
-- where the game leaves the toimen seat out by itself. No screen geometry, no
-- modulo arithmetic and no assumption about the number of seats is involved, and
-- a seat the game cannot place (position 0, a seat missing from a three-player
-- table) is never renamed.
local function opponent_seats(self)
    local data = self.player_datas
    if type(data) ~= 'table' then return nil end
    if type(self.seat) ~= 'number' or self.seat < 1 then return nil end
    if type(self.seat2LocalPosition) ~= 'function' then return nil end
    local seats, positions, count = {}, {}, 0
    for seat = 1, 4 do
        if seat ~= self.seat and data[seat] then
            local ok, position = pcall(self.seat2LocalPosition, self, seat)
            if ok and type(position) == 'number' and position > 1 then
                count = count + 1
                seats[count] = seat
                positions[count] = position
                -- Insertion sort by descending position: at most three entries.
                local index = count
                while index > 1 and positions[index - 1] < positions[index] do
                    seats[index - 1], seats[index] = seats[index], seats[index - 1]
                    positions[index - 1], positions[index] = positions[index], positions[index - 1]
                    index = index - 1
                end
            end
        end
    end
    if count == 0 then return nil end
    return seats, count
end

-- Raw (pre-prefix) server name the modder cached for one account, or nil.
local function cached_original(account)
    if account <= 0 then return nil end
    local call = rawget(_G, '__majmax_naming_original')
    if type(call) ~= 'function' then return nil end
    local ok, original = pcall(call, string.format('%.0f', account))
    if ok and type(original) == 'string' and original ~= '' then return original end
    return nil
end

-- The name one opponent seat has to show, as (name, managed).
--
-- `managed` marks the seats whose displayed name this module owns: the table's
-- own getter then has to report the same string. A name this module never wrote
-- stays unmanaged, so its remark flag and the game's own filtering still apply.
--
-- `record` is the value this module replaced for that seat, so a later normal
-- mode can put the table back exactly as it was without asking anyone.
local function desired(seat, account, index, current, state)
    local label = state.labels_mode and state.labels[index] or nil
    if type(label) == 'string' and label ~= '' then
        -- The label replaces the name only: the region tag is owned by the
        -- independent showServer switch, exactly like it is in the authGame
        -- rewrite, so a preset or custom label is never shown bare while the
        -- switch is on and never inherits a tag while it is off.
        return prefix(account, state.show) .. label, true
    end

    -- Normal mode, or a custom slot left empty: the response that built this
    -- table may still carry a label from the previous choice, so put the server
    -- name captured for that seat back. The cached name is the raw one, so the
    -- tag is composed from the *current* switch here as well - turning the
    -- switch off cannot keep an old prefix and turning it back on cannot double
    -- one. A robot or a seat without an account id has nothing to restore.
    local cached = cached_original(account)
    if cached then return prefix(account, state.show) .. cached, true end

    -- No server name to restore: the name the table already carries was not
    -- written by this module, so only the switch may still act on it, and only
    -- on the exact tag of this exact account. Turning the switch off drops a tag
    -- the response was built with; turning it back on puts that same tag back.
    -- Nothing is ever invented here: a name without a tag may legitimately have
    -- none (the response was built with the switch off, or the seat is a robot).
    local record = N.raw and N.raw[seat]
    local base = (record and record.account == account) and record.value or current
    if state.show then return base, false end
    local tag = prefix(account, true)
    if tag ~= '' and base:sub(1, #tag) == tag then return base:sub(#tag + 1), false end
    return base, false
end

-- Writes the wanted name into the seat data. Idempotent: it is called once per
-- pump tick, so a name the game rebuilds (a new round re-reads
-- `player_datas_temp`) is restored without another user edit, and a table that
-- already shows the right names costs no writes and no redraw.
local function apply(self, state)
    local data = self.player_datas
    if type(data) ~= 'table' then return false end
    if N.data ~= data then
        -- A new table replaced the seat data: what was replaced belongs to the
        -- previous one.
        N.data = data
        N.raw = {}
        N.managed = {}
    end
    local seats, count = opponent_seats(self)
    if not seats then return false end
    if not N.raw then N.raw = {} end
    if not N.managed then N.managed = {} end
    local changed, seen = false, {}
    for index = 1, count do
        local seat = seats[index]
        local player = data[seat]
        local current = player and player.nickname
        if type(current) == 'string' then
            local account = player.account_id
            if type(account) ~= 'number' or account < 0 then account = 0 end
            local want, managed = desired(seat, account, index, current, state)
            if want ~= current then
                local record = N.raw[seat]
                if not record or record.account ~= account or current ~= record.written then
                    -- First write for this seat, or the game replaced the name
                    -- behind this module: remember what is there now.
                    record = { account = account, value = current }
                    N.raw[seat] = record
                end
                record.written = want
                player.nickname = want
                changed = true
            end
            seen[seat] = true
            N.managed[seat] = managed and want or nil
        end
    end
    -- A seat that is no longer an opponent of this table (a four-player table
    -- becoming a three-player one, or the main body switching to that seat) must
    -- not keep a name this module owns: it gets exactly the name that was there
    -- before the first replacement.
    for seat in pairs(N.managed) do
        if not seen[seat] then
            N.managed[seat] = nil
            local record = N.raw[seat]
            local player = data[seat]
            if record and player and player.nickname == record.written
                    and type(player.account_id) == 'number'
                    and player.account_id == record.account then
                player.nickname = record.value
                N.raw[seat] = nil
                changed = true
            end
        end
    end
    return changed
end

-- The client's own redraw entry point for the seat names. It is the call the
-- client makes right after it changes the seat content of a live table itself
-- (see the header: the `true` is the flag those call sites pass).
local function redraw()
    local info = g('UI_DesktopInfo')
    local instance = info and rawget(info, 'Inst')
    if type(instance) ~= 'table' then return false end
    -- UI instances inherit their methods from the class table via __index.
    -- The outer tick is protected by pcall for lazy ToLua lookup failures.
    local refresh = instance.RefreshSeat
    if type(refresh) ~= 'function' then return false end
    local ok = pcall(refresh, instance, true)
    return ok
end

-- The filtered getter, wrapped only so a consumer of it agrees with the table.
-- A seat this module did not rename keeps the game's own two return values, the
-- friend remark flag included.
local function install()
    local desktop = g('DesktopMgr')
    if type(desktop) ~= 'table' then return false end
    local original = rawget(desktop, 'get_player_name')
    if type(original) ~= 'function' then return false end
    if original == N.wrapper then return true end
    local function get_player_name(self, seat)
        local ok, name, isRemark = pcall(original, self, seat)
        if not ok then
            -- A naming bug must never reach the game: the untouched getter
            -- decides, exactly as if this module were not loaded.
            return original(self, seat)
        end
        local managed = N.managed and N.managed[seat]
        if managed then return managed, false end
        return name, isRemark
    end
    N.wrapper = get_player_name
    desktop.get_player_name = get_player_name
    return true
end

-- Cheap enough to run every tick: a class table that is still missing, and one
-- the game rebuilt after a reload, both mean "install again".
local function is_installed()
    local desktop = g('DesktopMgr')
    return type(desktop) == 'table' and rawget(desktop, 'get_player_name') == N.wrapper
end

local function tick()
    if not is_installed() then install() end
    local state = read_state()
    N.state = state
    if not state then return end
    local self = table_now()
    if not self then return end
    if state.signature ~= N.signature then
        N.signature = state.signature
        if state.force then N.dirty = true end
    end
    if apply(self, state) then N.dirty = true end
    if not N.dirty then return end
    if redraw() then
        N.dirty = nil
        N.attempts = nil
        return
    end
    N.attempts = (N.attempts or REFRESH_ATTEMPTS) - 1
    if N.attempts <= 0 then
        N.dirty = nil
        N.attempts = nil
    end
end

-- Entry point of the native pump, called next to the auto-discard tick.
function _G.__majmax_naming_tick()
    pcall(tick)
end
