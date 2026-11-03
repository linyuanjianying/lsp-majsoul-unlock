-- Commands are data, never evaluated as Lua. This file runs only on Unity's main thread.
local A = rawget(_G, '__majmax_auto') or { seen = {}, order = {} }
_G.__majmax_auto = A
local function ack(id, state, reason)
    if type(__majmax_auto_ack) == 'function' and A.json then
        __majmax_auto_ack(A.json.encode({id = id or '', state = state, reason = reason or ''}))
    end
end
local function mark(id)
    A.seen[id] = true
    A.order[#A.order + 1] = id
    if #A.order > 64 then A.seen[table.remove(A.order, 1)] = nil end
end
local function waiting(id, reason)
    if A.waitId ~= id or A.waitReason ~= reason then
        A.waitId = id; A.waitReason = reason
        ack(id, 'waiting', reason)
    end
end
-- Keep claim diagnostics bounded to fixed reason codes: no tiles, account data or RPC payloads.
local function claimWaiting(id, reason)
    waiting(id, 'claim_' .. reason)
end
local function claimProbe(id, stage)
    if A.probeId ~= id then A.probeId = id; A.probeStages = {} end
    if A.probeStages[stage] then return end
    A.probeStages[stage] = true
    ack(id, 'probe', 'claim_' .. stage)
end
local function tile(value)
    local honors = { E='1z', S='2z', W='3z', N='4z', P='5z', F='6z', C='7z' }
    if honors[value] then return honors[value] end
    if type(value) ~= 'string' then return nil end
    if value:match('^5[mps]r$') then return '0'..value:sub(2,2) end
    if value:match('^[1-9][mps]$') then return value end
end
local function install()
    if not A.json then local ok, json = pcall(require, 'cjson'); if ok then A.json = json end end
    if not A.json or not MJNetMgr or type(MJNetMgr.SendRequest) ~= 'function' then return false end
    if not A.networkHooked then
        A.networkHooked = true
        local original = MJNetMgr.SendRequest
        function MJNetMgr:SendRequest(service, method, request, callback, ...)
            if service == 'FastTest' and method == 'syncGame' and A.resyncing then
                -- Rebuild the assistant from auth + the complete round, not a partial delta.
                request.round_id = '-1'; request.step = 1000000
                A.resyncing = nil
            end
            if service ~= 'FastTest' or (method ~= 'inputOperation' and method ~= 'inputChiPengGang') then
                return original(self, service, method, request, callback, ...)
            end
            local id = A.executing
            local external = request and (request.auto_operation or (tonumber(request.timeuse) or 0) >= 1000000)
            ack(id, id and 'submitted' or (external and 'external' or 'manual'))
            if id and A.claimExecuting == id then ack(id, 'probe', 'claim_network_intercepted') end
            local wrapped = function(error, response, ...)
                if id then
                    if A.claimExecuting == id then ack(id, 'probe', 'claim_network_callback') end
                    local code = response and response.error and response.error.code
                    ack(id, (not error and response and (not code or code == 0)) and 'accepted' or 'failed')
                end
                if callback then return callback(error, response, ...) end
            end
            return original(self, service, method, request, wrapped, ...)
        end
    end
    return true
end
local function active(object)
    if not object then return false end
    local gameObject = object.gameObject or (object.transform and object.transform.gameObject)
    return gameObject and gameObject.activeInHierarchy
end
local function seconds()
    return UnityEngine and UnityEngine.Time and UnityEngine.Time.realtimeSinceStartup
end
local function delaySeconds(command)
    local lower, upper = command.delayMinMs, command.delayMaxMs
    if type(lower) ~= 'number' or type(upper) ~= 'number' or lower ~= lower or upper ~= upper
            or lower < 0 or upper > 5000 or lower > upper or lower % 100 ~= 0 or upper % 100 ~= 0 then
        lower, upper = 2000, 5000
    end
    return (lower == upper and lower or math.random(lower, upper)) / 1000
end
local function continuation(command)
    local t = seconds()
    local d = DesktopMgr and DesktopMgr.Inst
    local net = MJNetMgr and MJNetMgr.Inst
    local mode = d and d.game_config and d.game_config.mode and d.game_config.mode.mode
    if not A.armed or not t or not d or not DesktopMgr.IsActive() or not GameUtility
            or d.mode ~= GameUtility.EMJ_Mode.play or d.duringReconnect or d.time_stopped
            or not net or not net:IsOK() or (mode ~= 1 and mode ~= 2 and mode ~= 11 and mode ~= 12) then
        A.continuation = nil; A.resyncDue = nil; return
    end
    -- Only known game result/next-round controls; never starts a new match.
    local candidates = {
        {UI_Win, '_onConfirm'}, {UI_HuleShow, 'Btn_Confirm'},
        {UI_ScoreChange, 'Btn_Confirm'}, {UI_ConfirmNewRound, 'Btn_Confirm'},
        {UI_HangUpWarn, 'Btn_Confirm', true}
    }
    for _, item in ipairs(candidates) do
        local class, method, root = item[1], item[2], item[3]
        if class and type(class.OnShow) == 'function' and not class._majmaxShowHook then
            class._majmaxShowHook = true
            local original = class.OnShow
            function class:OnShow(...)
                self._majmaxShow = (self._majmaxShow or 0) + 1
                return original(self, ...)
            end
        end
        local ui = class and class.Inst
        local owner = ui and (root and ui.root or ui)
        local btn = owner and owner.btn_confirm
        if ui and owner and active(ui) and active(btn) and btn.interactable ~= false
                and not ui.locking and not owner.locking and not ui.isDoAnimation
                and type(owner[method]) == 'function' then
            local key = tostring(ui._majmaxShow or 0)..':'..tostring(ui.current_index or 0)
                ..':'..tostring(ui.during_show_liujumanguan or false)
            local pending = A.continuation
            if not pending or pending.ui ~= ui or pending.key ~= key then
                pending = {ui=ui, key=key, due=t + delaySeconds(command)}
                A.continuation = pending
            end
            A.resyncDue = nil
            if not pending.done and t >= pending.due then
                pending.done = true
                local ok = pcall(owner[method], owner)
                ack('', ok and 'continued' or 'continuation_failed')
            end
            return
        end
    end
    A.continuation = nil
    if command.control == 'mode' and command.resync and d.gameing
            and type(net._connectSuccess) == 'function' and t >= (A.nextResync or 0) then
        A.resyncDue = A.resyncDue or (t + delaySeconds(command))
        if t >= A.resyncDue then
            A.resyncDue = nil; A.nextResync = t + 30; A.resyncing = true
            local ok = pcall(net._connectSuccess, net)
            if not ok then A.resyncing = nil end
            ack('', ok and 'resync' or 'resync_failed')
        end
    else A.resyncDue = nil end
end
local function execute(command)
    local id = command.id
    if type(id) ~= 'string' or #id > 100 or A.seen[id] then return end
    local action = command.action
    local claimKind = type(action) == 'table' and (action.kind == 'chi' or action.kind == 'pon')
    -- A newer heartbeat may be captured between provider polling and this tick.
    -- Wait for renewed proof; the controller revokes changed decisions/timeouts.
    if __majmax_auto_current() ~= 'yes' then
        if claimKind then claimWaiting(id, 'capture_proof') else waiting(id, 'capture_proof') end
        return
    end
    local d = DesktopMgr and DesktopMgr.Inst
    local r = d and d.mainrole
    local enums = GameUtility and GameUtility.EMJ_Mode
    local ops = GameUtility and GameUtility.E_PlayerOperation
    if not d or not r or not enums or not ops or not d.gameing or not DesktopMgr.IsActive()
            or d.mode ~= enums.play or d.duringReconnect or d.time_stopped
            or not MJNetMgr or not MJNetMgr.Inst or not MJNetMgr.Inst:IsOK() then
        waiting(id, claimKind and 'claim_game_window' or 'game_window'); return
    end
    -- The network snapshot arrives before the game's draw animation finishes.
    if d.current_step ~= command.step then
        if claimKind then claimWaiting(id, 'game_step')
        else waiting(id, 'game_step '..tostring(d.current_step)..' expected '..tostring(command.step)) end
        return
    end
    if r._mouse_downed or r._during_drag then mark(id); ack(id, 'manual'); return end
    if r._during_liqi or r._during_reveal or r._during_reveal_liqi then
        mark(id); ack(id, 'special_selection'); return
    end
    local mode = d.game_config and d.game_config.mode and d.game_config.mode.mode
    if mode ~= 1 and mode ~= 2 and mode ~= 11 and mode ~= 12 then mark(id); ack(id, 'unsupported_mode'); return end
    local context = command.autoContext
    -- DesktopMgr uses 1-based round winds; Akagi exposes mjai wind names.
    local winds = {E=1, S=2, W=3, N=4}
    if type(context) ~= 'table' or d.seat ~= command.seat + 1 or d.index_chang ~= winds[context.wind]
            or d.index_ju ~= context.kyoku or d.index_ben ~= context.honba then
        mark(id)
        if claimKind then ack(id, 'round_mismatch')
        else ack(id, 'round_mismatch', string.format('seat %s/%s wind %s/%s ju %s/%s ben %s/%s',
            tostring(d.seat), tostring(command.seat+1), tostring(d.index_chang), tostring(winds[context.wind]),
            tostring(d.index_ju), tostring(context.kyoku), tostring(d.index_ben), tostring(context.honba))) end
        return
    end
    if type(action) ~= 'table' then mark(id); ack(id, 'unknown_action'); return end
    -- NewGame sorts the complete opening hand; only its first dealer discard
    -- delegates moqie to the original client method. Never relax tile/hand proof.
    local opening = context.openingDealerDiscard == true and action.clientOpeningDraw == true
        and (action.kind == 'discard' or action.kind == 'riichi')
        and context.dealer == command.seat and context.phase == 'wait_act'
        and context.riichi ~= true and type(context.melds) == 'table' and #context.melds == 0
        and type(command.hand) == 'table' and #command.hand == 14
    local kind, operation = action.kind, nil
    for _, op in ipairs(d.oplist or {}) do
        if op.type == action.type then operation = op end
    end
    if kind ~= 'pass' and not operation then
        if kind == 'chi' or kind == 'pon' then claimWaiting(id, 'operation_missing') end
        return
    end
    if kind == 'discard' or kind == 'riichi' then
        if not r._can_discard or type(r._DoDiscardTile) ~= 'function' or type(r._setChoosePai) ~= 'function' then return end
        -- A winning window must be handled by the explicit AI recommendation.
        for _, op in ipairs(d.oplist or {}) do
            if kind == 'discard' and (op.type == ops.zimo or op.type == ops.rong) then
                mark(id); ack(id, 'win_available'); return
            end
        end
    end
    local actual, expected, selected = {}, {}, nil
    for _, value in ipairs(command.hand or {}) do
        local name = tile(value)
        if not name then mark(id); ack(id, 'invalid_hand'); return end
        expected[#expected + 1] = name
    end
    for _, view in ipairs(r._handpai or {}) do
        if not view.pai or type(view.pai.ToString) ~= 'function' then mark(id); ack(id, 'unknown_hand'); return end
        local name = view.pai:ToString()
        actual[#actual + 1] = name
        if name == command.gameTile and view.valid and not view.pai.baida and not view.is_open then
            if command.tsumogiri == (view == r._last_tile) or opening then selected = view end
        end
    end
    table.sort(actual); table.sort(expected)
    if #expected == 0 or table.concat(actual, ',') ~= table.concat(expected, ',') then
        mark(id); ack(id, 'hand_mismatch'); return
    end
    if claimKind then claimProbe(id, 'hand_verified') end
    local own, claim
    if claimKind then
        -- Unity property access may throw before the ordinary readiness check can report.
        -- Fixed stage codes only: do not transmit exception strings or game objects.
        local ok
        ok, claim = pcall(function() return UI_ChiPengHu and UI_ChiPengHu.Inst end)
        if not ok then claimWaiting(id, 'ui_instance_exception'); return end
    else
        own = UI_LiqiZimo and UI_LiqiZimo.Inst
        claim = UI_ChiPengHu and UI_ChiPengHu.Inst
    end
    local function ready(ui)
        return ui and ui.transform and ui.transform.gameObject.activeInHierarchy and not ui.on_do_operation
            and ui.container_btns and ui.container_btns.transform.gameObject.activeInHierarchy
    end
    local function button(ui, name, method)
        local btn = ui and ui.container_btns and ui.container_btns[name]
        return ready(ui) and btn and btn.transform.gameObject.activeInHierarchy
            and type(ui.container_btns[method]) == 'function'
    end
    local run
    if kind == 'discard' or kind == 'riichi' then
        if not selected then mark(id); ack(id, 'illegal_tile'); return end
        if kind == 'riichi' then
            if not button(own, 'btn_lizhi', 'Btn_Lizhi') then return end
            local found = false
            for _, value in ipairs(operation.combination or {}) do if value:gsub('0','5') == action.tile:gsub('0','5') then found = true end end
            if not found then mark(id); ack(id, 'illegal_combination'); return end
            -- DesktopMgr builds liqi_select from the current server operation list.
            local valid = false
            for _, value in ipairs(d.liqi_select or {}) do if value:ToString():gsub('0','5') == action.tile:gsub('0','5') then valid = true end end
            if not valid then return end
        end
        run = function()
            if kind == 'riichi' then
                own.container_btns:Btn_Lizhi(ops.liqi)
                if not r._during_liqi then error('riichi selection did not activate') end
            end
            if not selected.valid then error('riichi tile unavailable') end
            r:_setChoosePai(selected, false); r:_DoDiscardTile()
            if type(r._resetMouseState) == 'function' then r:_resetMouseState() end
        end
    elseif kind == 'chi' or kind == 'pon' or kind == 'kan' then
        local uiReady, isReady = pcall(ready, claim)
        if not uiReady then
            if claimKind then claimWaiting(id, 'ui_readiness_exception') end
            return
        end
        if not isReady then
            if claimKind then claimWaiting(id, 'ui_not_ready') end
            return
        end
        if claimKind then claimProbe(id, 'ui_ready') end
        local targetOk, tileMatches, seatValue = pcall(function()
            if not d.lastqipai or type(d.lastqipai.ToString) ~= 'function' then return nil, nil end
            return d.lastqipai:ToString() == action.tile, d.lastqipai_seat
        end)
        -- Prefer the captured server discard to unstable presentation fields.
        -- Legacy commands without protocol context must still pass the UI guard.
        local source = context.lastDiscard
        if type(source) == 'table' then
            if context.phase ~= 'wait_response' or tile(source.tile) ~= action.tile
                    or source.seat ~= action.target then
                mark(id); ack(id, 'target_mismatch'); return
            end
        elseif not targetOk or not tileMatches or seatValue ~= action.target + 1 then
            mark(id); ack(id, 'target_mismatch'); return
        end
        if claimKind then
            if not targetOk then claimProbe(id, 'target_read_ignored')
            elseif tileMatches == nil then claimProbe(id, 'target_unavailable_ignored')
            elseif not tileMatches then claimProbe(id, 'target_tile_ignored')
            elseif seatValue ~= action.target + 1 then claimProbe(id, 'target_seat_ignored')
            else claimProbe(id, 'target_verified') end
        end
        local index = action.index
        local combinationOk, combinationMatches = pcall(function()
            return type(index) == 'number' and index >= 0 and index % 1 == 0
                and operation.combination and operation.combination[index+1] == action.combination
        end)
        if not combinationOk then
            if claimKind then claimWaiting(id, 'operation_combination_exception') end
            return
        end
        if not combinationMatches then mark(id); ack(id, 'illegal_combination'); return end
        if claimKind then claimProbe(id, 'operation_combination_verified') end
        if kind == 'kan' then
            if index ~= 0 or not button(claim, 'btn_gang', 'Btn_Gang') then return end
            run = function() claim.container_btns:Btn_Gang() end
        else
            local dataOk, values = pcall(function()
                local data = claim.data
                return data and data[kind == 'chi' and 'chi' or 'peng']
            end)
            if not dataOk then claimWaiting(id, 'ui_data_exception'); return end
            if not values then claimWaiting(id, 'ui_data_missing'); return end
            claimProbe(id, 'ui_data_present')
            local valueOk, valueMatches = pcall(function() return values[index+1] == action.combination end)
            if not valueOk then claimWaiting(id, 'ui_combination_exception'); return end
            if not valueMatches then claimWaiting(id, 'ui_combination_mismatch'); return end
            claimProbe(id, 'ui_combination_verified')
            local detailOk, detail = pcall(function() return claim.OnClickDetail end)
            if not detailOk then claimWaiting(id, 'detail_read_exception'); return end
            if type(detail) ~= 'function' then claimWaiting(id, 'detail_unavailable'); return end
            claimProbe(id, 'detail_available')
            run = function()
                claimProbe(id, 'assign_operation_begin')
                claim.choosed_op = action.type
                claimProbe(id, 'assign_operation_done')
                claimProbe(id, 'detail_call_begin')
                detail(claim, index, 1)
                claimProbe(id, 'detail_call_returned')
            end
        end
    elseif kind == 'ankan' or kind == 'kakan' then
        if not ready(own) or type(own.OnClickDetail) ~= 'function' then return end
        local values = kind == 'ankan' and own.com_an_gang or own.com_add_gang
        local index = action.index
        if type(index) ~= 'number' or index < 0 or index % 1 ~= 0
                or (operation.combination or {})[index+1] ~= action.combination then
            mark(id); ack(id, 'illegal_combination'); return
        end
        if not values or values[index+1] ~= action.combination then return end
        local offset = kind == 'ankan' and #(own.com_add_gang or {}) or 0
        run = function() own:OnClickDetail(index + offset) end
    elseif kind == 'hora' then
        if action.target == command.seat and action.type == ops.zimo then
            if not button(own, 'btn_zimo', 'Btn_Zimo') then return end
            run = function() own.container_btns:Btn_Zimo() end
        elseif action.target ~= command.seat and action.type == ops.rong then
            if not button(claim, 'btn_hu', 'Btn_Hu') then return end
            run = function() claim.container_btns:Btn_Hu() end
        end
    elseif kind == 'kita' then
        if not button(own, 'btn_babei', 'Btn_Babei') then return end
        local north = false
        for _, value in ipairs(actual) do if value == '4z' then north = true end end
        if not north or action.moqie ~= (r._last_tile ~= nil and r._last_tile.pai:ToString() == '4z') then
            mark(id); ack(id, 'illegal_tile'); return
        end
        run = function() own.container_btns:Btn_Babei() end
    elseif kind == 'abort' then
        if not button(own, 'btn_jiuzhongjiupai', 'Btn_JiuZhongJiuPai') then return end
        run = function() own.container_btns:Btn_JiuZhongJiuPai() end
    elseif kind == 'pass' then
        if action.method ~= 'inputChiPengGang' or not button(claim, 'btn_cancel', 'Btn_Cancel') then return end
        run = function() claim.container_btns:Btn_Cancel() end
    end
    if not run then mark(id); ack(id, 'unknown_action'); return end
    if claimKind then claimProbe(id, 'commit_ready') end
    -- Recheck after all game-side checks and immediately before committing.
    if __majmax_auto_current() ~= 'yes' then
        if claimKind then claimWaiting(id, 'capture_proof') else waiting(id, 'capture_proof') end
        return
    end
    if claimKind then claimProbe(id, 'capture_verified') end
    mark(id)
    A.executing = id
    if claimKind then A.claimExecuting = id; claimProbe(id, 'run_begin') end
    local ok = pcall(run)
    A.executing = nil
    A.claimExecuting = nil
    if not ok then ack(id, claimKind and 'claim_run_exception' or 'game_exception')
    elseif claimKind then claimProbe(id, 'invoked') end
end
-- Enumerates every enabled camera, projects one sample position through each, and
-- returns them in a stable, de-duplicated order (main first, then allCameras). This
-- lets reportHandCoords score cameras by how well they actually land hand tiles in
-- the viewport instead of trusting Camera.main or index order.
local function enumerateCameras()
    local Camera = UnityEngine and UnityEngine.Camera
    if not Camera then return {} end
    local out, seen = {}, {}
    local main = Camera.main
    if main and main.enabled then
        out[#out + 1] = main
        seen[main] = true
    end
    local all = Camera.allCameras
    if all then
        -- Unity exposes Camera.allCameras as a C# array (0-based, Length) in most
        -- Lua bindings, but a few bridges convert it to a 1-based Lua table. Detect
        -- the base by whether index 0 holds an element so both shapes are covered.
        local count = all.Length or (type(all) == 'table' and #all) or 0
        local base = (all[0] ~= nil) and 0 or 1
        for i = base, base + count - 1 do
            local c = all[i]
            if c and c.enabled and not seen[c] then
                seen[c] = true
                out[#out + 1] = c
            end
        end
    end
    return out
end

-- Projects the concealed hand positions through one camera and returns, for that
-- camera, the number of tiles that land inside the normalized viewport plus a
-- bounded bounding box. `z<0` (behind the camera) is counted as outside.
local function projectThrough(cam, positions, screenW, screenH)
    local inView, behind, total = 0, 0, 0
    local minNx, maxNx, minNy, maxNy = 2, -2, 2, -2
    local sumNy = 0
    for _, p in ipairs(positions) do
        total = total + 1
        local ok, sp = pcall(cam.WorldToScreenPoint, cam, p)
        if not ok or type(sp) ~= 'table' then
            -- Projection threw (e.g. a disposed camera); treat as not in view and
            -- not behind, so it neither inflates behind nor inView.
        else
            local z = sp.z or 0
            local x, y = sp.x or 0, sp.y or 0
            if z < 0 then
                behind = behind + 1
            else
                local nx = x / screenW
                local ny = (screenH - y) / screenH
                if nx < minNx then minNx = nx end
                if nx > maxNx then maxNx = nx end
                if ny < minNy then minNy = ny end
                if ny > maxNy then maxNy = ny end
                sumNy = sumNy + ny
                if nx >= 0 and nx <= 1 and ny >= 0 and ny <= 1 then
                    inView = inView + 1
                end
            end
        end
    end
    if total == 0 then return { inView = 0, behind = 0, minNx = 2, maxNx = -2, minNy = 2, maxNy = -2, meanNy = 0, total = 0 } end
    local projected = total - behind
    return {
        inView = inView,
        behind = behind,
        minNx = minNx,
        maxNx = maxNx,
        minNy = minNy,
        maxNy = maxNy,
        meanNy = projected > 0 and (sumNy / projected) or 0,
        total = total,
    }
end

-- Chooses the camera whose projection actually places the concealed hand in the
-- lower region of the viewport. The hand sits along the bottom edge of the screen,
-- so a correct projection has most tiles with 0 <= nx <= 1 and ny near 1 (bottom).
-- A camera whose tiles all land off-screen, or whose tiles sit in the *upper* half
-- (mean ny < 0.5, the mirror image of a hand), is rejected rather than trusted.
--
-- Scoring is purely observational: it never rescales or clamps a coordinate to fake
-- an in-viewport result. If no camera lands at least half the hand in view, nil is
-- returned so the caller keeps the text hint and records a bounded reason.
local function pickCamera(positions, screenW, screenH)
    local cams = enumerateCameras()
    if #cams == 0 or #positions == 0 then return nil, nil end
    local best, bestScore = nil, nil
    for _, cam in ipairs(cams) do
        local r = projectThrough(cam, positions, screenW, screenH)
        if r.total > 0 and r.inView > 0 then
            -- Require the projected hand to sit in the lower half of the screen;
            -- an upper-half projection is a mirrored/effect camera, not the hand.
            if r.meanNy >= 0.5 then
                -- Primary: number of tiles in view. Tie-break: mean ny closer to the
                -- bottom (larger), then a more compact horizontal spread.
                local score = { inView = r.inView, meanNy = r.meanNy, span = -(r.maxNx - r.minNx) }
                local better = bestScore == nil
                if not better then
                    if score.inView ~= bestScore.inView then better = score.inView > bestScore.inView
                    elseif score.meanNy ~= bestScore.meanNy then better = score.meanNy > bestScore.meanNy
                    else better = score.span > bestScore.span end
                end
                if better then best, bestScore = cam, score end
            end
        end
    end
    if best == nil then return nil, nil end
    return best, bestScore
end

local function reportHandCoords()
    local d = DesktopMgr and DesktopMgr.Inst
    local r = d and d.mainrole
    local Screen = UnityEngine and UnityEngine.Screen
    local screenW = Screen and Screen.width
    local screenH = Screen and Screen.height
    if not d or not d.gameing or not r or not r._handpai or not screenW or not screenH or screenW <= 0 or screenH <= 0 or not A.json then return end

    -- Only concealed hand views map one-to-one onto the protocol hand. Melded
    -- (open) views and the baida wildcard sit in _handpai too, but they have no
    -- counterpart in the AI's tehai, so reporting them shifts every later index.
    -- Collect the concealed, projectable views once, then project them through the
    -- chosen camera below.
    local concealedViews, positions = {}, {}
    local step = d.current_step or 0
    local openCount, baidaCount = 0, 0
    for i, view in ipairs(r._handpai) do
        if view then
            local pai = view.pai
            local isOpen = view.is_open == true
            local isBaida = pai and pai.baida == true
            if isOpen then
                openCount = openCount + 1
            elseif isBaida then
                baidaCount = baidaCount + 1
            else
                local transform = view.transform
                local position = transform and transform.position
                if view.valid and pai and position then
                    concealedViews[#concealedViews + 1] = view
                    positions[#positions + 1] = position
                end
            end
        end
    end

    -- Select the camera by observing where it actually projects the hand, never by
    -- Camera.main or index order. Fall back to nil (text hint only) when no camera
    -- places the hand in the lower viewport.
    local cam = pickCamera(positions, screenW, screenH)

    local coords = {}
    local invalidCount, behindCount = 0, 0
    local minNx, maxNx, minNy, maxNy = 2, -2, 2, -2
    if cam then
        for i, view in ipairs(concealedViews) do
            local pai = view.pai
            local position = view.transform.position
            local sp = cam:WorldToScreenPoint(position)
            -- WorldToScreenPoint returns z<0 when the point is behind the camera;
            -- those projections are mirror-imaged and unusable.
            if sp.z < 0 then
                behindCount = behindCount + 1
                invalidCount = invalidCount + 1
            else
                -- Normalized viewport fractions: the consumer multiplies them back by
                -- the overlay's own measured size, so Unity's internal render
                -- resolution never drifts against the Android display.
                local nx = sp.x / screenW
                local ny = (screenH - sp.y) / screenH
                coords[#coords + 1] = {
                    tile = pai:ToString(),
                    nx = math.floor(nx * 10000 + 0.5) / 10000,
                    ny = math.floor(ny * 10000 + 0.5) / 10000,
                    last = (view == r._last_tile)
                }
                if nx < minNx then minNx = nx end
                if nx > maxNx then maxNx = nx end
                if ny < minNy then minNy = ny end
                if ny > maxNy then maxNy = ny end
            end
        end
    else
        invalidCount = #positions
    end

    -- Bounded diagnostics: emit once per distinct geometry fingerprint, never per tick.
    -- min/max nx/ny are recorded so an out-of-viewport capture is visible in the
    -- log instead of being silently filtered away on the Kotlin side.
    local diag = {
        screenW = screenW,
        screenH = screenH,
        handpai = #r._handpai,
        concealed = #coords,
        open = openCount,
        baida = baidaCount,
        invalid = invalidCount,
        behind = behindCount,
        minNx = math.floor(minNx * 10000 + 0.5) / 10000,
        maxNx = math.floor(maxNx * 10000 + 0.5) / 10000,
        minNy = math.floor(minNy * 10000 + 0.5) / 10000,
        maxNy = math.floor(maxNy * 10000 + 0.5) / 10000,
        cam = tostring(cam and cam.name or ''),
        ortho = cam ~= nil and cam.orthographic == true,
    }
    -- A bounded per-camera projection fingerprint so a real device can be diagnosed
    -- when no candidate covers the hand, or to confirm the winning camera really is
    -- the one that places the hand in the lower viewport. Emitted once per geometry
    -- fingerprint (not per tick), with name/ortho/inView/total/bbox/meanNy for each
    -- enabled camera. Fields are separated by '|' and cameras by '+'; no ';' is used
    -- because the Kotlin side splits the detail string on ';' into key=value tokens.
    local camRows = {}
    for _, c in ipairs(enumerateCameras()) do
        if #camRows < 6 then
            local rr = projectThrough(c, positions, screenW, screenH)
            camRows[#camRows + 1] = string.format('%s|%s|%d/%d|%.2f,%.2f,%.2f,%.2f|%.2f',
                tostring(c.name or '?'), c.orthographic and 'ortho' or 'persp',
                rr.inView, rr.total,
                rr.minNx, rr.maxNx, rr.minNy, rr.maxNy,
                rr.meanNy)
        end
    end
    diag.cams = table.concat(camRows, '+')

    if #coords > 0 then
        -- The signature must change whenever the hand's *contents* change, not just
        -- its length and the two end coordinates. The step counter alone does not
        -- advance during a drawn-tile animation, so a same-step reshuffle or draw
        -- would otherwise reuse stale anchors and never resend. Folding the tile
        -- sequence into the signature forces a resend exactly when the hand changes.
        --
        -- The game's `_handpai` views are reordered transiently while a draw/discard
        -- animation is playing: a capture taken mid-animation can report one tile at
        -- a wrong x (e.g. `6p` where `2z` belongs). That jitter must not be shipped
        -- to the overlay, because the previous content-only signature would suppress
        -- the corrected follow-up capture (same tiles, same count) and strand a badge
        -- on the wrong tile. So we fold the x sequence into the signature *and* hold
        -- a non-monotonic capture back until the next tick reports a settled hand.
        --
        -- The orthographic hand camera mirrors the world x, so a *settled* hand can be
        -- strictly increasing or strictly decreasing in x depending on the camera that
        -- actually renders it. A jittered frame is the only case where the direction
        -- flips mid-sequence (one tile reporting a wrong intermediate x), so we accept
        -- a single consistent direction and reject a capture that changes direction.
        local monotonic = true
        local dir = nil
        local tiles, xs = {}, {}
        for i, c in ipairs(coords) do
            tiles[#tiles + 1] = c.tile
            xs[#xs + 1] = c.nx
            if i > 1 then
                local d = c.nx - coords[i - 1].nx
                if d == 0 then
                    monotonic = false
                elseif dir == nil then
                    dir = d > 0
                elseif (d > 0) ~= dir then
                    monotonic = false
                end
            end
        end
        if monotonic then
            local sig = tostring(step) .. ':' .. tostring(#coords) .. ':' .. table.concat(tiles, ',')
                .. ':' .. table.concat(xs, ',')
            if A.lastCoordsSig ~= sig then
                A.lastCoordsSig = sig
                -- Wrap the coordinate array with the Unity source viewport it was
                -- normalized against, so the overlay can map normalized fractions
                -- back to the *source* surface and then to its own content frame.
                -- The wrapper object keeps the parser able to read the bare array
                -- (legacy) while carrying the source dimensions per capture.
                ack('', 'hand_coords', A.json.encode({
                    screenW = screenW,
                    screenH = screenH,
                    coords = coords,
                }))
            end
        end
    end

    local diagSig = tostring(screenW) .. ':' .. tostring(screenH) .. ':' .. tostring(#r._handpai) .. ':' .. tostring(#coords)
        .. ':' .. tostring(openCount) .. ':' .. tostring(baidaCount) .. ':' .. tostring(invalidCount)
        .. ':' .. tostring(behindCount) .. ':' .. tostring(diag.ortho) .. ':' .. tostring(diag.cam)
        .. ':' .. tostring(diag.cams or '')
    if A.lastDiagSig ~= diagSig then
        A.lastDiagSig = diagSig
        diag.tiles = #coords > 0 and table.concat({ coords[1].tile, coords[#coords].tile }, ',') or ''
        ack('', 'hand_geometry', A.json.encode(diag))
    end
end

function __majmax_auto_tick()
    local ok = pcall(function()
        if not install() then return end
        pcall(reportHandCoords)
        if type(__majmax_auto_command) ~= 'function' then return end
        local text = __majmax_auto_command()
        if not text or text == '' then A.armed = false; A.continuation = nil; A.resyncDue = nil; return end
        local command = A.json.decode(text)
        if type(command) == 'table' then
            A.armed = (command.control == 'mode' and command.enabled == true) or command.unattended == true
            continuation(command)
            if command.control ~= 'mode' then execute(command) end
        end
    end)
    if not ok then ack('', 'adapter_exception') end
end
