-- lua5.1 tools/tests/test_auto_discard.lua. Fakes never send game traffic.
local asset = 'hook-probe/src/main/assets/liqi_config/ui/MajsoulMaxAutoDiscard.lua'
local count = 0
local function fixture()
    __majmax_auto = nil
    UnityEngine={Time={realtimeSinceStartup=0}}
    UI_Win=nil; UI_HuleShow=nil; UI_ScoreChange=nil; UI_ConfirmNewRound=nil; UI_HangUpWarn=nil
    local state = { sent = {}, acks = {}, current = true }
    local function view(name)
        return { valid=true, is_open=false, pai={ToString=function() return name end}, transform={} }
    end
    local normal, red, drawn = view('5m'), view('0m'), view('9p')
    local role = { _can_discard=true, _handpai={normal,red,drawn}, _last_tile=drawn }
    state.role = role
    state.command = {id='one',step=3,seat=0,hand={'5m','5mr','9p'},gameTile='0m',tsumogiri=false,
        action={kind='discard',type=1,tile='0m',moqie=false,method='inputOperation'},
        autoContext={wind='E',kyoku=1,honba=0}}
    cjson = {encode=function(value) state.acks[#state.acks+1]=value; return 'ack' end,
        decode=function() return state.command end}
    package.loaded.cjson = cjson
    __majmax_auto_command = function() return state.leaseExpired and '' or 'command' end
    __majmax_auto_current = function() return state.current and 'yes' or 'no' end
    __majmax_auto_ack = function() end
    GameUtility = {EMJ_Mode={play=1},E_PlayerOperation={dapai=1,eat=2,peng=3,an_gang=4,ming_gang=5,add_gang=6,liqi=7,zimo=8,rong=9,jiuzhongjiupai=10,babei=11}}
    MJNetMgr = {SendRequest=function(self,service,method,request,callback,...)
        state.sent[#state.sent+1]=request
        if callback then callback(nil,{error={code=0}}) end
        return 'original'
    end,Inst={IsOK=function() return true end}}
    setmetatable(MJNetMgr.Inst,{__index=MJNetMgr})
    DesktopMgr = { IsActive=function() return true end, Inst={mainrole=role,gameing=true,mode=1,
        seat=1,index_chang=1,index_ju=1,index_ben=0,current_step=3,oplist={{type=1}},game_config={mode={mode=1}}}}
    function role:_setChoosePai(value) self.selected=value end
    function role:_DoDiscardTile()
        MJNetMgr.Inst:SendRequest('FastTest','inputOperation',
            {type=1,tile=self.selected.pai:ToString(),moqie=self.selected==self._last_tile},function() end)
        self._can_discard=false
    end
    local function send(method,op,index,moqie,cancel)
        return MJNetMgr.Inst:SendRequest('FastTest',method,{type=op,index=index,moqie=moqie,cancel_operation=cancel},function() end)
    end
    local function ui()
        local u={transform={gameObject={activeInHierarchy=true}},container_btns={transform={gameObject={activeInHierarchy=true}}}}
        local btn=u.container_btns
        for _,name in ipairs({'lizhi','zimo','hu','gang','babei','jiuzhongjiupai','cancel'}) do
            btn['btn_'..name]={transform={gameObject={activeInHierarchy=true}}}
        end
        return u
    end
    UI_LiqiZimo={Inst=ui()}; UI_ChiPengHu={Inst=ui()}
    local own,claim=UI_LiqiZimo.Inst,UI_ChiPengHu.Inst
    own.com_add_gang={}; own.com_an_gang={}; claim.data={}
    function own.container_btns:Btn_Lizhi() role._during_liqi=true end
    function own.container_btns:Btn_Zimo() send('inputOperation',8,0) end
    function own.container_btns:Btn_Babei() send('inputOperation',11,nil,role._last_tile.pai:ToString()=='4z') end
    function own.container_btns:Btn_JiuZhongJiuPai() send('inputOperation',10,0) end
    function own:OnClickDetail(index)
        send('inputOperation', index < #self.com_add_gang and 6 or 4, index < #self.com_add_gang and index or index-#self.com_add_gang)
    end
    function claim.container_btns:Btn_Gang() send('inputChiPengGang',5,0) end
    function claim.container_btns:Btn_Hu() send('inputChiPengGang',9,0) end
    function claim.container_btns:Btn_Cancel() send('inputChiPengGang',nil,nil,nil,true) end
    function claim:OnClickDetail(index) send('inputChiPengGang',self.choosed_op,index) end
    DesktopMgr.Inst.lastqipai={ToString=function() return '3m' end}; DesktopMgr.Inst.lastqipai_seat=4
    local originalDiscard=role._DoDiscardTile
    function role:_DoDiscardTile()
        if self._during_liqi then
            send('inputOperation',7,nil,self.selected==self._last_tile); self._during_liqi=false
        else originalDiscard(self) end
    end
    dofile(asset)
    return state
end
local function test(name,run)
    run(fixture()); count=count+1; print('PASS '..name)
end
test('red five is distinct and duplicate command is not replayed',function(s)
    __majmax_auto_tick(); __majmax_auto_tick()
    assert(#s.sent==1 and s.sent[1].tile=='0m' and not s.sent[1].moqie)
    assert(s.acks[1].state=='submitted' and s.acks[2].state=='accepted')
end)
test('normal five stays normal',function(s)
    s.command.gameTile='5m'; s.command.action.tile='5m'; __majmax_auto_tick(); assert(s.sent[1].tile=='5m')
end)
test('tsumogiri uses the drawn tile',function(s)
    s.command.gameTile='9p'; s.command.tsumogiri=true; s.command.action.tile='9p'; s.command.action.moqie=true
    __majmax_auto_tick(); assert(s.sent[1].moqie)
end)
test('incorrect tsumogiri never substitutes another copy',function(s)
    s.command.tsumogiri=true; __majmax_auto_tick(); assert(#s.sent==0 and s.acks[1].state=='illegal_tile')
end)

local function openingDealer(s)
    for i=1,11 do
        local name='1m'
        s.command.hand[#s.command.hand+1]=name
        s.role._handpai[#s.role._handpai+1]={valid=true,is_open=false,pai={ToString=function() return name end}}
    end
    s.command.autoContext.openingDealerDiscard=true
    s.command.autoContext.dealer=0; s.command.autoContext.phase='wait_act'
    s.command.autoContext.melds={}; s.command.autoContext.riichi=false
    s.command.action.clientOpeningDraw=true
end

test('opening dealer uses client draw identity after whole hand sorting',function(s)
    openingDealer(s)
    -- The virtual protocol draw is a red five; the game's sorted last tile is 9p.
    s.command.tsumogiri=true; s.command.action.moqie=true
    __majmax_auto_tick()
    assert(#s.sent==1 and s.sent[1].tile=='0m' and not s.sent[1].moqie)
end)

test('opening dealer may discard the client last tile despite a virtual hand cut',function(s)
    openingDealer(s)
    s.command.gameTile='9p'; s.command.action.tile='9p'
    __majmax_auto_tick()
    assert(#s.sent==1 and s.sent[1].tile=='9p' and s.sent[1].moqie)
end)

test('opening exception requires protocol dealer and phase proof',function(s)
    openingDealer(s)
    s.command.tsumogiri=true; s.command.action.moqie=true
    s.command.autoContext.dealer=1
    __majmax_auto_tick()
    assert(#s.sent==0 and s.acks[1].state=='illegal_tile')
end)
test('revoked or newer capture never executes',function(s)
    s.current=false; __majmax_auto_tick(); assert(#s.sent==0 and s.acks[1].state=='waiting')
    s.current=true; __majmax_auto_tick(); assert(#s.sent==1)
end)
test('hand mismatch fails closed',function(s)
    s.command.hand={'5m','5m','9p'}; __majmax_auto_tick(); assert(#s.sent==0 and s.acks[1].state=='hand_mismatch')
end)
test('wrong round fails closed',function(s)
    s.command.autoContext.honba=1; __majmax_auto_tick(); assert(#s.sent==0 and s.acks[1].state=='round_mismatch')
end)
test('draw animation waits for matching game step and legal window',function(s)
    DesktopMgr.Inst.current_step=2; __majmax_auto_tick(); assert(#s.sent==0)
    DesktopMgr.Inst.current_step=3; s.role._can_discard=false; __majmax_auto_tick(); assert(#s.sent==0)
    s.role._can_discard=true; __majmax_auto_tick(); assert(#s.sent==1)
end)
test('invalid selected tile cannot discard',function(s)
    s.role._handpai[2].valid=false; __majmax_auto_tick(); assert(#s.sent==0)
end)
test('win is never silently passed',function(s)
    DesktopMgr.Inst.oplist={{type=1},{type=8}}; __majmax_auto_tick(); assert(#s.sent==0 and s.acks[1].state=='win_available')
end)
test('riichi selection and manual dragging stop automatic input',function(s)
    s.role._during_liqi=true; __majmax_auto_tick(); assert(#s.sent==0 and s.acks[1].state=='special_selection')
end)
test('manual network input is reported and preserves callback',function(s)
    __majmax_auto_tick()
    local called=false
    local result=MJNetMgr.Inst:SendRequest('FastTest','inputChiPengGang',{},function() called=true end)
    assert(result=='original' and called and s.acks[#s.acks].state=='manual')
end)
test('unsupported modes never execute',function(s)
    DesktopMgr.Inst.game_config.mode.mode=5; __majmax_auto_tick(); assert(#s.sent==0)
end)
local function operation(s,kind,op,combo)
    s.command.action={kind=kind,type=op,index=0,target=3,tile='3m',combination=combo,method='inputChiPengGang'}
    DesktopMgr.Inst.oplist={{type=op,combination={combo}}}; s.role._can_discard=false
end
test('riichi sends declaration rather than ordinary discard',function(s)
    s.command.action={kind='riichi',type=7,tile='0m',moqie=false,method='inputOperation'}
    DesktopMgr.Inst.oplist={{type=1},{type=7,combination={'0m'}}}
    DesktopMgr.Inst.liqi_select={s.role._handpai[2].pai}
    __majmax_auto_tick(); assert(#s.sent==1 and s.sent[1].type==7)
end)
test('chi selects exact combination index including red five',function(s)
    operation(s,'chi',2,'0m|4m'); s.command.action.index=1
    DesktopMgr.Inst.oplist[1].combination={'4m|5m','0m|4m'}
    UI_ChiPengHu.Inst.data.chi=DesktopMgr.Inst.oplist[1].combination
    __majmax_auto_tick(); assert(s.sent[1].type==2 and s.sent[1].index==1)
end)
test('pon uses the selected server combination',function(s)
    operation(s,'pon',3,'5m|0m'); UI_ChiPengHu.Inst.data.peng={'5m|0m'}
    __majmax_auto_tick(); assert(s.sent[1].type==3)
end)
local function claimReason(s)
    for i=#s.acks,1,-1 do
        local row=s.acks[i]
        if row.state == 'waiting' and row.reason and row.reason:match('^claim_') then return row.reason end
    end
end
test('claim UI not ready reports bounded diagnostic',function(s)
    operation(s,'pon',3,'5m|0m'); UI_ChiPengHu.Inst.transform.gameObject.activeInHierarchy=false
    __majmax_auto_tick(); assert(#s.sent==0 and claimReason(s)=='claim_ui_not_ready')
end)
test('claim UI getter exception reports a fixed diagnostic',function(s)
    operation(s,'pon',3,'5m|0m')
    UI_ChiPengHu=setmetatable({}, {__index=function() error('private UI getter error') end})
    __majmax_auto_tick()
    assert(#s.sent==0 and claimReason(s)=='claim_ui_instance_exception')
end)
test('claim readiness exception reports a fixed diagnostic',function(s)
    operation(s,'pon',3,'5m|0m')
    UI_ChiPengHu.Inst.transform=setmetatable({}, {__index=function() error('private transform error') end})
    __majmax_auto_tick()
    assert(#s.sent==0 and claimReason(s)=='claim_ui_readiness_exception')
end)
test('claim UI missing data reports bounded diagnostic',function(s)
    operation(s,'chi',2,'0m|4m')
    __majmax_auto_tick(); assert(#s.sent==0 and claimReason(s)=='claim_ui_data_missing')
end)
test('claim UI mismatched combination reports bounded diagnostic',function(s)
    operation(s,'pon',3,'5m|0m'); UI_ChiPengHu.Inst.data.peng={'5m|5m'}
    __majmax_auto_tick(); assert(#s.sent==0 and claimReason(s)=='claim_ui_combination_mismatch')
end)
test('claim UI missing detail method reports bounded diagnostic',function(s)
    operation(s,'pon',3,'5m|0m'); UI_ChiPengHu.Inst.data.peng={'5m|0m'}
    UI_ChiPengHu.Inst.OnClickDetail=nil
    __majmax_auto_tick(); assert(#s.sent==0 and claimReason(s)=='claim_detail_unavailable')
end)
test('claim called but not submitted is distinguishable',function(s)
    operation(s,'pon',3,'5m|0m'); UI_ChiPengHu.Inst.data.peng={'5m|0m'}
    UI_ChiPengHu.Inst.OnClickDetail=function(self) self.detailOpened=true end
    __majmax_auto_tick(); assert(#s.sent==0 and UI_ChiPengHu.Inst.detailOpened)
    assert(s.acks[#s.acks].state=='probe' and s.acks[#s.acks].reason=='claim_invoked')
    local probes={}
    for _,row in ipairs(s.acks) do if row.state=='probe' then probes[row.reason]=true end end
    for _,stage in ipairs({'claim_ui_ready','claim_target_verified','claim_operation_combination_verified',
            'claim_ui_data_present','claim_ui_combination_verified','claim_detail_available',
            'claim_commit_ready','claim_capture_verified','claim_run_begin','claim_assign_operation_begin',
            'claim_assign_operation_done','claim_detail_call_begin','claim_detail_call_returned'}) do
        assert(probes[stage], stage)
    end
end)
test('claim call exception is reported without exception text',function(s)
    operation(s,'chi',2,'0m|4m'); UI_ChiPengHu.Inst.data.chi={'0m|4m'}
    UI_ChiPengHu.Inst.OnClickDetail=function() error('sensitive bridge error') end
    __majmax_auto_tick(); assert(#s.sent==0 and s.acks[#s.acks].state=='claim_run_exception')
    assert(not tostring(s.acks[#s.acks].reason):find('sensitive'))
end)
test('open kan uses claim window',function(s)
    operation(s,'kan',5,'3m|3m|3m'); __majmax_auto_tick(); assert(s.sent[1].type==5)
end)
test('stale target seat cannot claim',function(s)
    operation(s,'pon',3,'5m|0m'); DesktopMgr.Inst.lastqipai_seat=2
    __majmax_auto_tick(); assert(#s.sent==0 and s.acks[#s.acks].state=='target_mismatch')
end)
test('changed combination never substitutes red for normal five',function(s)
    operation(s,'pon',3,'5m|0m'); DesktopMgr.Inst.oplist[1].combination={'5m|5m'}
    __majmax_auto_tick(); assert(#s.sent==0 and s.acks[#s.acks].state=='illegal_combination')
end)
test('ankan offsets past kakan entries',function(s)
    operation(s,'ankan',4,'5m|5m|5m|0m')
    UI_LiqiZimo.Inst.com_add_gang={'1p|1p|1p|1p'}; UI_LiqiZimo.Inst.com_an_gang={'5m|5m|5m|0m'}
    __majmax_auto_tick(); assert(s.sent[1].type==4 and s.sent[1].index==0)
end)
test('kakan selects its own zero based index',function(s)
    operation(s,'kakan',6,'5m|5m|5m|0m'); UI_LiqiZimo.Inst.com_add_gang={'5m|5m|5m|0m'}
    __majmax_auto_tick(); assert(s.sent[1].type==6 and s.sent[1].index==0)
end)
test('tsumo uses own turn win button',function(s)
    operation(s,'hora',8); s.command.action.target=0
    __majmax_auto_tick(); assert(s.sent[1].type==8)
end)
test('ron uses claim win button',function(s)
    operation(s,'hora',9); __majmax_auto_tick(); assert(s.sent[1].type==9)
end)
test('pass explicitly cancels claim',function(s)
    operation(s,'pass',0); DesktopMgr.Inst.oplist={{type=2}}
    __majmax_auto_tick(); assert(s.sent[1].cancel_operation)
end)
test('north extraction preserves drawn north flag',function(s)
    operation(s,'kita',11); s.command.action.moqie=false
    s.command.hand[1]='N'; s.role._handpai[1].pai.ToString=function() return '4z' end
    __majmax_auto_tick(); assert(s.sent[1].type==11 and not s.sent[1].moqie)
end)
test('nine terminals uses abort button',function(s)
    operation(s,'abort',10); __majmax_auto_tick(); assert(s.sent[1].type==10)
end)
test('hidden action window waits instead of firing',function(s)
    operation(s,'hora',9); UI_ChiPengHu.Inst.transform.gameObject.activeInHierarchy=false
    __majmax_auto_tick(); assert(#s.sent==0)
end)
local function resultWindow(s, className, method)
    local ui={transform={gameObject={activeInHierarchy=true}},btn_confirm={gameObject={activeInHierarchy=true},interactable=true}}
    ui[method]=function(self) s.continued=(s.continued or 0)+1 end
    _G[className]={Inst=ui, OnShow=function() end}
    s.command={control='mode',enabled=true,resync=false}
    DesktopMgr.Inst.gameing=false
    return ui
end
test('win result confirms only after two to five seconds and only once',function(s)
    resultWindow(s,'UI_Win','_onConfirm')
    __majmax_auto_tick(); assert(not s.continued)
    local due=__majmax_auto.continuation.due; assert(due>=2 and due<=5)
    UnityEngine.Time.realtimeSinceStartup=due-0.001; __majmax_auto_tick(); assert(not s.continued)
    UnityEngine.Time.realtimeSinceStartup=due; __majmax_auto_tick(); __majmax_auto_tick(); assert(s.continued==1)
end)

test('configured fixed delay applies to settlement including zero',function(s)
    for _,delay in ipairs({0,1300,5000}) do
        local ui=resultWindow(s,'UI_Win','_onConfirm'); ui.current_index=delay
        s.command.delayMinMs=delay; s.command.delayMaxMs=delay
        local start=UnityEngine.Time.realtimeSinceStartup
        local before=s.continued or 0
        __majmax_auto_tick()
        assert(__majmax_auto.continuation.due==start+delay/1000)
        if delay>0 then
            assert((s.continued or 0)==before)
            UnityEngine.Time.realtimeSinceStartup=start+delay/1000-0.001; __majmax_auto_tick()
            assert((s.continued or 0)==before)
        end
        UnityEngine.Time.realtimeSinceStartup=start+delay/1000; __majmax_auto_tick()
        assert(s.continued==before+1)
        UI_Win=nil; __majmax_auto_tick()
    end
end)

test('invalid delay configuration falls back to existing safe defaults',function(s)
    for _,range in ipairs({{-100,5000},{0,5100},{2000,1000},{101,2000},{'bad',2000},{0,0/0}}) do
        resultWindow(s,'UI_Win','_onConfirm')
        s.command.delayMinMs=range[1]; s.command.delayMaxMs=range[2]
        __majmax_auto_tick()
        assert(__majmax_auto.continuation.due>=2 and __majmax_auto.continuation.due<=5)
        UI_Win=nil; __majmax_auto_tick()
    end
end)

test('settlement delay changes only affect the next result page',function(s)
    local ui=resultWindow(s,'UI_Win','_onConfirm')
    s.command.delayMinMs=1000; s.command.delayMaxMs=1000; __majmax_auto_tick()
    s.command.delayMinMs=3000; s.command.delayMaxMs=3000
    UnityEngine.Time.realtimeSinceStartup=1; __majmax_auto_tick(); assert(s.continued==1)
    ui.current_index=1; __majmax_auto_tick(); assert(__majmax_auto.continuation.due==4)
end)
test('all round result dialogs use their existing confirm methods',function(s)
    for _,name in ipairs({'UI_HuleShow','UI_ScoreChange','UI_ConfirmNewRound'}) do
        resultWindow(s,name,'Btn_Confirm'); __majmax_auto_tick()
        UnityEngine.Time.realtimeSinceStartup=__majmax_auto.continuation.due; __majmax_auto_tick()
        _G[name]=nil
    end
    assert(s.continued==3 and #s.sent==0)
end)
test('hidden or animating result controls cannot continue',function(s)
    local ui=resultWindow(s,'UI_Win','_onConfirm'); ui.isDoAnimation=true
    __majmax_auto_tick(); assert(not __majmax_auto.continuation)
    ui.isDoAnimation=false; ui.btn_confirm.gameObject.activeInHierarchy=false
    __majmax_auto_tick(); assert(not __majmax_auto.continuation)
end)
test('disabling mode or losing provider lease cancels continuation',function(s)
    resultWindow(s,'UI_Win','_onConfirm'); __majmax_auto_tick()
    s.command.enabled=false; UnityEngine.Time.realtimeSinceStartup=10; __majmax_auto_tick(); assert(not s.continued)
    s.command.enabled=true; __majmax_auto_tick(); s.leaseExpired=true
    UnityEngine.Time.realtimeSinceStartup=20; __majmax_auto_tick(); assert(not s.continued and not __majmax_auto.armed)
end)
test('a new result page receives a fresh delay',function(s)
    local ui=resultWindow(s,'UI_Win','_onConfirm'); ui.current_index=1
    __majmax_auto_tick(); UnityEngine.Time.realtimeSinceStartup=5; __majmax_auto_tick(); assert(s.continued==1)
    ui.current_index=2; __majmax_auto_tick(); assert(s.continued==1)
    UnityEngine.Time.realtimeSinceStartup=__majmax_auto.continuation.due; __majmax_auto_tick(); assert(s.continued==2)
end)
test('resync uses auth and forces a complete restore with cooldown',function(s)
    s.command={control='mode',enabled=true,resync=true}
    MJNetMgr.Inst._connectSuccess=function(self)
        s.resynced=(s.resynced or 0)+1
        self:SendRequest('FastTest','syncGame',{round_id='E1',step=3},function() end)
    end
    __majmax_auto_tick(); assert(not s.resynced)
    UnityEngine.Time.realtimeSinceStartup=__majmax_auto.resyncDue; __majmax_auto_tick()
    assert(s.resynced==1 and s.sent[1].round_id=='-1' and s.sent[1].step==1000000)
    UnityEngine.Time.realtimeSinceStartup=10; __majmax_auto_tick(); assert(s.resynced==1)
    MJNetMgr.Inst:SendRequest('FastTest','syncGame',{round_id='E2',step=7},function() end)
    assert(s.sent[2].round_id=='E2' and s.sent[2].step==7)
end)

test('configured zero delay resync still respects cooldown',function(s)
    s.command={control='mode',enabled=true,resync=true,delayMinMs=0,delayMaxMs=0}
    MJNetMgr.Inst._connectSuccess=function() s.resynced=(s.resynced or 0)+1 end
    __majmax_auto_tick(); assert(s.resynced==1)
    UnityEngine.Time.realtimeSinceStartup=29.999; __majmax_auto_tick(); assert(s.resynced==1)
    UnityEngine.Time.realtimeSinceStartup=30; __majmax_auto_tick(); assert(s.resynced==2)
end)
test('between rounds and during reconnect never trigger extra resync',function(s)
    s.command={control='mode',enabled=true,resync=true}; DesktopMgr.Inst.gameing=false
    __majmax_auto_tick(); assert(not __majmax_auto.resyncDue)
    DesktopMgr.Inst.gameing=true; DesktopMgr.Inst.duringReconnect=true
    __majmax_auto_tick(); assert(not __majmax_auto.resyncDue)
end)
test('built in timeout is external rather than manual input',function(s)
    s.command={control='mode',enabled=true}; __majmax_auto_tick()
    MJNetMgr.Inst:SendRequest('FastTest','inputOperation',{auto_operation=true},function() end)
    assert(s.acks[#s.acks].state=='external')
end)
test('restored game step waits for proof without disabling or replaying',function(s)
    s.command.step=9; DesktopMgr.Inst.current_step=8; __majmax_auto_tick()
    assert(#s.sent==0 and s.acks[1].state=='waiting')
    __majmax_auto_tick(); assert(#s.acks==1)
    DesktopMgr.Inst.current_step=9; __majmax_auto_tick(); assert(#s.sent==1)
end)
test('protocol target tolerates stale UI but never mismatched server actor',function(s)
    operation(s,'pon',3,'5m|0m'); UI_ChiPengHu.Inst.data.peng={'5m|0m'}
    s.command.autoContext.phase='wait_response'; s.command.autoContext.lastDiscard={tile='3m',seat=3}
    DesktopMgr.Inst.lastqipai_seat=2; DesktopMgr.Inst.lastqipai=nil
    __majmax_auto_tick(); assert(#s.sent==1)
end)
test('protocol target mismatch never claims even if UI looks right',function(s)
    operation(s,'pon',3,'5m|0m'); UI_ChiPengHu.Inst.data.peng={'5m|0m'}
    s.command.autoContext.phase='wait_response'; s.command.autoContext.lastDiscard={tile='3m',seat=2}
    __majmax_auto_tick(); assert(#s.sent==0 and s.acks[#s.acks].state=='target_mismatch')
end)
test('claim does not read unrelated own action window',function(s)
    operation(s,'pon',3,'5m|0m'); UI_ChiPengHu.Inst.data.peng={'5m|0m'}
    UI_LiqiZimo=setmetatable({}, {__index=function() error('not loaded') end})
    __majmax_auto_tick(); assert(#s.sent==1)
end)
test('waiting claim diagnostics remain bounded across ticks',function(s)
    operation(s,'pon',3,'5m|0m'); __majmax_auto_tick(); local n=#s.acks
    for i=1,50 do __majmax_auto_tick() end
    assert(#s.acks==n and #s.sent==0)
end)

-- Coordinate reporting lives behind the shared __majmax_auto_tick entrypoint, but
-- the game-side fixtures above never provide a camera, so it is exercised in a
-- self-contained environment here. It must report a normalized coordinate range
-- (so an out-of-viewport capture is visible), and it must resend when the hand's
-- tile *contents* change even if its length and end coordinates do not.
local function coordFixture()
    __majmax_auto = nil
    local screenW, screenH = 2376, 1080
    local cam = { name='Main Camera', orthographic=false, enabled=true }
    function cam:WorldToScreenPoint(p)
        return { x = p.nx * screenW, y = (1 - p.ny) * screenH, z = p.z or 5 }
    end
    UnityEngine = {
        Camera = { main = cam, allCameras = { cam } },
        Screen = { width = screenW, height = screenH },
        Time = { realtimeSinceStartup = 0 },
    }
    local acks = {}
    cjson = { encode = function(v) acks[#acks + 1] = v; return 'ack' end,
        decode = function() return {} end }
    package.loaded.cjson = cjson
    __majmax_auto_command = function() return '' end
    __majmax_auto_ack = function() end
    local function view(tile, nx, ny)
        local pai = { t = tile, baida = false }
        function pai:ToString() return self.t end
        return { pai = pai, valid = true, is_open = false,
            transform = { position = { nx = nx, ny = ny, z = 5 } } }
    end
    local role = { _handpai = {}, _last_tile = nil }
    DesktopMgr = { Inst = { gameing = true, current_step = 3, mainrole = role } }
    dofile(asset)
    return acks, role, function() __majmax_auto_tick() end, view
end
test('hand coordinates report a normalized range and resend on content change',function()
    local acks, role, tick, view = coordFixture()
    role._handpai = { view('1m', 0.20, 0.80), view('2m', 0.28, 0.80),
        view('3m', 0.36, 0.80), view('4m', 0.44, 0.80) }
    role._last_tile = role._handpai[1]
    tick()
    -- The mock encoder stores the raw table passed to it, so the coords array and
    -- the geometry table both land in acks directly, followed by the wrapped ack.
    local coordsSeen, geom
    for _, a in ipairs(acks) do
        if a.state == 'hand_coords' then coordsSeen = true end
        if type(a.handpai) == 'number' and a.screenW then geom = a end
    end
    assert(coordsSeen, 'expected a hand_coords ack')
    assert(geom, 'expected a hand_geometry record')
    assert(geom.handpai == 4 and geom.concealed == 4)
    assert(math.abs(geom.minNx - 0.2) < 0.001 and math.abs(geom.maxNx - 0.44) < 0.001)
    assert(math.abs(geom.minNy - 0.8) < 0.001 and math.abs(geom.maxNy - 0.8) < 0.001)
    assert(geom.behind == 0)
    local n = #acks
    tick()  -- identical hand must not resend
    assert(#acks == n, 'identical hand must not resend hand_coords')
    -- Same length and same end tiles, but the middle tile changes: must resend.
    role._handpai[2] = view('5m', 0.28, 0.80)
    tick()
    assert(#acks > n, 'content change must resend hand_coords')
    local resent = false
    for i = n + 1, #acks do if acks[i].state == 'hand_coords' then resent = true end end
    assert(resent, 'content change must emit a new hand_coords')
end)
test('coordinates behind the camera are counted and excluded',function()
    local acks, role, tick, view = coordFixture()
    role._handpai = { view('1m', 0.20, 0.80), view('2m', 0.28, 0.80) }
    role._handpai[1].transform.position.z = -5  -- behind the camera
    role._last_tile = role._handpai[2]
    tick()
    local geom
    for _, a in ipairs(acks) do if type(a.handpai) == 'number' and a.screenW then geom = a end end
    assert(geom.concealed == 1 and geom.behind == 1 and geom.invalid == 1,
        'one tile behind camera must be counted, not projected')
end)
-- Camera selection must pick the camera whose projection actually lands the
-- concealed hand in the lower viewport, not Camera.main or the first entry. The
-- real device showed every tile projected to ny ~= 2.0 (off-screen below) because
-- get3DCamera picked a non-orthographic scene/UI camera and rejected the correct
-- one; these fixtures reproduce that shape and assert the fix.
local function multiCamFixture(cams, camNameForMain)
    __majmax_auto = nil
    local screenW, screenH = 2376, 1080
    UnityEngine = {
        Camera = { main = camNameForMain and cams[camNameForMain] or nil, allCameras = {} },
        Screen = { width = screenW, height = screenH },
        Time = { realtimeSinceStartup = 0 },
    }
    local list = UnityEngine.Camera.allCameras
    for name, c in pairs(cams) do list[#list + 1] = c end
    local acks = {}
    cjson = { encode = function(v) acks[#acks + 1] = v; return 'ack' end,
        decode = function() return {} end }
    package.loaded.cjson = cjson
    __majmax_auto_command = function() return '' end
    __majmax_auto_ack = function() end
    -- The asset's install() gate requires a network manager, so provide a stub so
    -- reportHandCoords actually runs regardless of test ordering.
    MJNetMgr = { SendRequest = function() end, Inst = { IsOK = function() return true end } }
    local function view(tile, x, y, z)
        local pai = { t = tile, baida = false }
        function pai:ToString() return self.t end
        return { pai = pai, valid = true, is_open = false,
            transform = { position = { wx = x, wy = y, wz = z or 5 } } }
    end
    local role = { _handpai = {}, _last_tile = nil }
    DesktopMgr = { Inst = { gameing = true, current_step = 3, mainrole = role } }
    dofile(asset)
    return acks, role, function() __majmax_auto_tick() end, view
end

-- A camera maps a world position to screen pixels. `z` comes back negative when
-- the point is behind it; otherwise the caller supplies the screen x/y directly.
local function cam(name, ortho, project)
    local c = { name = name, orthographic = ortho, enabled = true }
    function c:WorldToScreenPoint(p)
        local sp = project(p)
        return { x = sp[1], y = sp[2], z = sp[3] or 5 }
    end
    return c
end

local HAND = { { '1m', 0.20, 0.82 }, { '2m', 0.28, 0.82 }, { '3m', 0.36, 0.82 }, { '4m', 0.44, 0.82 } }

test('camera selection prefers the orthographic hand camera over a misaligned Camera.main',function()
    local screenW, screenH = 2376, 1080
    local mainCam = cam('UI Camera', false, function(p)
        -- A UI/scene camera whose projection dumps every tile off-screen below
        -- (ny ~= 2.0), exactly matching the real-device GAME_GEOMETRY sample.
        return { p.wx, -screenH, 5 }
    end)
    local handCam = cam('GameHand Camera', true, function(p)
        -- The orthographic camera that actually renders the hand in the lower band.
        return { (1 - p.wx) * screenW, (1 - p.wy) * screenH, 5 }
    end)
    local acks, role, tick, view = multiCamFixture(
        { ['UI Camera'] = mainCam, ['GameHand Camera'] = handCam }, 'UI Camera')
    for _, t in ipairs(HAND) do role._handpai[#role._handpai + 1] = view(t[1], t[2], t[3]) end
    role._last_tile = role._handpai[1]
    tick()
    local coords, geom
    for _, a in ipairs(acks) do
        if a.state == 'hand_coords' then coords = a end
        if type(a.handpai) == 'number' and a.screenW then geom = a end
    end
    assert(geom and geom.cam == 'GameHand Camera', 'expected the hand camera, got ' .. tostring(geom and geom.cam))
    assert(geom.ortho == true, 'expected an orthographic hand camera')
    assert(coords, 'expected hand_coords from the correct camera')
    -- All tiles must land inside the viewport now.
    assert(geom.minNx >= 0 and geom.maxNx <= 1 and geom.minNy >= 0 and geom.maxNy <= 1,
        'projected hand must be inside the viewport')
end)

test('camera selection rejects an upper-half (mirrored) projection even if in view',function()
    local screenW, screenH = 2376, 1080
    local mirror = cam('Mirror Camera', true, function(p)
        -- Lands tiles "in view" but in the *upper* half: not the hand, a reflection.
        return { (1 - p.wx) * screenW, p.wy * screenH, 5 }
    end)
    local handCam = cam('GameHand Camera', true, function(p)
        return { (1 - p.wx) * screenW, (1 - p.wy) * screenH, 5 }
    end)
    local acks, role, tick, view = multiCamFixture(
        { ['Mirror Camera'] = mirror, ['GameHand Camera'] = handCam }, 'Mirror Camera')
    for _, t in ipairs(HAND) do role._handpai[#role._handpai + 1] = view(t[1], t[2], t[3]) end
    tick()
    local geom
    for _, a in ipairs(acks) do if type(a.handpai) == 'number' and a.screenW then geom = a end end
    assert(geom.cam == 'GameHand Camera', 'must not pick a mirror/effect camera')
end)

test('camera selection reports per-camera diagnostics and keeps text hint when none cover the hand',function()
    local screenW, screenH = 2376, 1080
    local wrong1 = cam('Scene Camera', false, function(p) return { p.wx, -screenH, 5 } end)
    local wrong2 = cam('Effect Camera', false, function(p) return { p.wx * 3, -screenH, 5 } end)
    local acks, role, tick, view = multiCamFixture(
        { ['Scene Camera'] = wrong1, ['Effect Camera'] = wrong2 }, 'Scene Camera')
    for _, t in ipairs(HAND) do role._handpai[#role._handpai + 1] = view(t[1], t[2], t[3]) end
    tick()
    local geom
    for _, a in ipairs(acks) do if type(a.handpai) == 'number' and a.screenW then geom = a end end
    assert(geom.cam == '', 'no valid camera means no camera is trusted')
    assert(geom.concealed == 0, 'no coordinates emitted when no camera covers the hand')
    assert(type(geom.cams) == 'string' and geom.cams ~= '', 'per-camera diagnostics must be recorded')
    -- A hand_coords must NOT be sent.
    for _, a in ipairs(acks) do assert(a.state ~= 'hand_coords', 'must not emit bogus coords') end
end)

test('camera selection is stable when the same camera renders every tile in view',function()
    local screenW, screenH = 2376, 1080
    local handCam = cam('GameHand Camera', false, function(p)
        return { (1 - p.wx) * screenW, (1 - p.wy) * screenH, 5 }
    end)
    local acks, role, tick, view = multiCamFixture({ ['GameHand Camera'] = handCam }, 'GameHand Camera')
    for _, t in ipairs(HAND) do role._handpai[#role._handpai + 1] = view(t[1], t[2], t[3]) end
    tick()
    local geom
    for _, a in ipairs(acks) do if type(a.handpai) == 'number' and a.screenW then geom = a end end
    assert(geom.cam == 'GameHand Camera' and geom.concealed == #HAND)
end)

print(string.format('%d automatic operation checks passed',count))

