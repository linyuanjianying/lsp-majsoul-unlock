-- lua5.1 tools/tests/test_tile_hints.lua
-- Presentation-only checks for the hand-tile recommendation badges. Nothing here
-- sends game traffic; the badge layer must never influence autoplay.
local asset = 'hook-probe/src/main/assets/liqi_config/ui/MajsoulMaxAutoDiscard.lua'
local count = 0

local function class(name) return setmetatable({}, { __call = function(self) return self end }) end

local function fixture()
    __majmax_auto = nil
    local state = { created = {}, destroyed = 0 }
    -- Minimal xLua surface: CS.UnityEngine.{GameObject,RectTransform,Vector2,Color,UI}
    -- and a global typeof, exactly as the game exposes them.
    local function gameObject(name)
        local go = {
            name = name, active = true, transform = {},
            SetActive = function(self, value) self.active = value end,
            SetParent = function(self, parent) self.parent = parent end,
            AddComponent = function(self) return self end,
        }
        state.created[#state.created + 1] = go
        return go
    end
    CS = {
        UnityEngine = {
            GameObject = gameObject,
            RectTransform = class('RectTransform'),
            Vector2 = function(x, y) return { x = x, y = y } end,
            Color = function(r, g, b, a) return { r = r, g = g, b = b, a = a } end,
            FontStyle = { Bold = 1 },
            TextAnchor = { MiddleCenter = 4 },
            UI = { Text = class('Text'), Outline = class('Outline') },
        },
    }
    typeof = function(value) return value end
    Object = { Destroy = function() state.destroyed = state.destroyed + 1 end }

    local function view(name)
        return { valid = true, is_open = false, pai = { ToString = function() return name end }, transform = {} }
    end
    local role = { _handpai = { view('1m'), view('5s'), view('5s'), view('9p') } }
    DesktopMgr = { Inst = { mainrole = role } }
    state.role = role

    cjson = { encode = function() return 'ack' end, decode = function() return {} end }
    package.loaded.cjson = cjson
    __majmax_auto_command = function() return nil end
    __majmax_auto_current = function() return 'no' end
    __majmax_auto_ack = function() end

    dofile(asset)
    -- The hint helpers are file-local, so the test loads the asset once more
    -- with an export line appended. Production loads the untouched asset.
    local source = assert(io.open(asset, 'r'))
    local body = source:read('*a')
    source:close()
    body = body .. '\n_G.__majmax_test_hint = hintRender\n'
    assert(loadstring(body, 'MajsoulMaxAutoDiscard-test'))()
    state.render = _G.__majmax_test_hint
    return state
end

local function test(name, run)
    run(fixture()); count = count + 1; print('PASS ' .. name)
end

test('badges paint one tile per ranked candidate, duplicates included', function(s)
    local created = s.created
    s.render({ recommendations = {
        { kind = 'discard', tile = '5s', percent = '94.7%' },
        { kind = 'discard', tile = '1m', percent = '2.3%' },
        { kind = 'discard', tile = '9p', percent = '1.2%' },
        { kind = 'pass', tile = '', percent = '0.1%' },
    } })
    assert(#created == 3, 'expected three badges, got ' .. #created)
    local parents = {}
    for _, go in ipairs(created) do
        assert(go.active, 'badge must stay active')
        assert(go.text, 'badge must carry a percentage label')
        parents[go.parent] = (parents[go.parent] or 0) + 1
    end
    -- Two duplicates must not share one tile.
    for parent, n in pairs(parents) do assert(n == 1, 'two badges landed on one tile') end
end)

test('identical snapshots do not rebuild the badges', function(s)
    local command = { recommendations = {
        { kind = 'discard', tile = '9p', percent = '50.0%' },
    } }
    s.render(command)
    local before = #s.created
    s.render(command)
    assert(#s.created == before, 'identical snapshot rebuilt badges')
end)

test('a new snapshot retires the previous badges first', function(s)
    s.render({ recommendations = {
        { kind = 'discard', tile = '9p', percent = '50.0%' },
    } })
    local before = #s.created
    s.render({ recommendations = {
        { kind = 'discard', tile = '1m', percent = '70.0%' },
    } })
    assert(s.destroyed >= 1, 'old badge was not destroyed')
    assert(#s.created == before + 1, 'new badge missing')
end)

test('tiles missing from hand never get a badge', function(s)
    s.render({ recommendations = {
        { kind = 'discard', tile = '7z', percent = '9.9%' },
    } })
    assert(#s.created == 0, 'badged a tile that is not in hand')
end)

test('missing game window degrades quietly', function(s)
    DesktopMgr = nil
    local ok = pcall(s.render, { recommendations = {} })
    assert(ok, 'badge layer threw without a game window')
end)

test('a fixed command with no recommendations clears standalone badges', function(s)
    s.render({ recommendations = {
        { kind = 'discard', tile = '9p', percent = '50.0%' },
    } })
    s.render(nil)
    assert(s.destroyed >= 1, 'clearing did not destroy badges')
end)

print(string.format('%d tile-hint checks passed', count))
