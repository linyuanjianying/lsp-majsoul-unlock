-- Opponent names for the table that is on screen right now.
--
-- The Rust modder rewrites the names inside the `authGame` response, which means
-- a change can only be seen in the next game. This module closes that gap on the
-- display side: it wraps `DesktopMgr:get_player_name` -- the getter the table
-- reads for every seat -- and redraws the table once when the shared choice (the
-- in-game control and the floating window write the same setting) changes.
--
-- It never touches the network, the protocol or the server payload, it runs only
-- on Unity's main thread from the native pump, and it only ever replaces the
-- names of the opponents of the current table: the local seat keeps its own name
-- and every failure falls back to the untouched getter.
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
    return {
        signature = raw,
        mode = mode,
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

-- Replacement name for one seat, or nil when the game's own answer stands.
-- `current` is what the game itself would display for that seat (the response's
-- nickname, i.e. whatever the authGame rewrite left there).
local function replacement(self, seat, state, current)
    local seats, count = opponent_seats(self)
    if not seats then return nil end
    local slot
    for index = 1, count do
        if seats[index] == seat then slot = index end
    end
    if not slot then return nil end
    local player = self.player_datas[seat]
    local account = player and player.account_id
    if type(account) ~= 'number' or account < 0 then account = 0 end

    local label = state.labels[slot]
    if type(label) == 'string' and label ~= '' then
        -- The label replaces the name only: the region tag is owned by the
        -- independent showServer switch, exactly like it is in the authGame
        -- rewrite, so a preset or custom label is never shown bare while the
        -- switch is on and never inherits a tag while it is off.
        return prefix(account, state.show) .. label
    end

    -- Normal mode, or a custom slot left empty: the response that built this
    -- table may still carry a label from the previous choice, so put the server
    -- name captured for that seat back. The cached name is the raw one, so the
    -- tag is composed from the *current* switch here as well - turning the
    -- switch off cannot keep an old prefix and turning it back on cannot double
    -- one. A robot or a seat without an account id has nothing to restore.
    local cached = cached_original(account)
    if cached then return prefix(account, state.show) .. cached end

    -- No cached name: this seat was not renamed, so the game's own answer
    -- stands. The one thing the switch still has to do on a live table is drop
    -- a prefix that the response was built with - only that exact tag of that
    -- exact account, and only while the switch is off. Nothing is ever added
    -- here: a name without the tag may legitimately have none (the response was
    -- built with the switch off, or the seat is a robot), and inventing one
    -- would modify a name this module never wrote.
    if state.show or account <= 0 or type(current) ~= 'string' then return nil end
    local tag = prefix(account, true)
    if tag ~= '' and current:sub(1, #tag) == tag then return current:sub(#tag + 1) end
    return nil
end

-- Resolves one seat. `original` keeps its own two return values, so the friend
-- remark flag the game computes stays intact for every seat whose rendered name
-- does not change (an unchanged replacement is reported as "no replacement").
local function resolve(original, self, seat)
    local name, isRemark = original(self, seat)
    local state = N.state
    if not state then return name, isRemark end
    local replaced = replacement(self, seat, state, name)
    if replaced and replaced ~= name then return replaced, false end
    return name, isRemark
end

local function install()
    local desktop = g('DesktopMgr')
    if type(desktop) ~= 'table' then return false end
    local original = rawget(desktop, 'get_player_name')
    if type(original) ~= 'function' then return false end
    if original == N.wrapper then return true end
    local function get_player_name(self, seat)
        local ok, name, isRemark = pcall(resolve, original, self, seat)
        if ok then return name, isRemark end
        -- A naming bug must never reach the game: the untouched getter decides,
        -- exactly as if this module were not loaded.
        return original(self, seat)
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

-- The client's own redraw entry point for the seat names.
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

local function tick()
    if not is_installed() then install() end
    local state = read_state()
    N.state = state
    if state and state.signature ~= N.signature then
        N.signature = state.signature
        N.attempts = state.force and REFRESH_ATTEMPTS or nil
    end
    if not N.attempts then return end
    if redraw() then
        N.attempts = nil
    else
        N.attempts = N.attempts - 1
        if N.attempts <= 0 then N.attempts = nil end
    end
end

-- Entry point of the native pump, called next to the auto-discard tick.
function _G.__majmax_naming_tick()
    pcall(tick)
end
