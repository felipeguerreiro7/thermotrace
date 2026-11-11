import {createApi, audience} from './api.mjs';
import {temperaturePanel} from './chart.mjs';

const api = createApi();
const root = document.querySelector('#app');
const labels = {staff:'Equipe ThermoTrace', cliente:'Cliente · dono da carga', contratante:'Contratante · transportadora', ambos:'Cliente e contratante'};
const statuses = {preparacao:'Em preparação', aguardando_aceite:'Aguardando aceite', aguardando_coleta:'Aguardando coleta', em_transporte:'Em transporte', entregue_aguardando_leitura:'Aguardando leitura final', concluida:'Concluída', cancelada:'Cancelada'};
// Andamento deduzido das etiquetas; o estado cadastral só aparece quando não há monitoramento.
const progress = {em_monitoramento:'Em monitoramento', leitura_final_recebida:'Leitura final recebida', cancelada:'Cancelada'};
const cargoStatus = r => progress[r.andamento] || statuses[r.status] || r.status;
const volumeStatus = {sem_etiqueta:'Sem etiqueta', vinculado:'Etiqueta vinculada', monitorando:'Em monitoramento', encerrado:'Leitura final recebida'};
const sessionStatus = {ativa:'em andamento', encerrada_normal:'leitura final recebida', encerrada_anormal:'encerrada com problema', abandonada:'abandonada'};
const shareStatus = {pendente:'Aguardando aceite', ativo:'Ativo', recusado:'Recusado', revogado:'Encerrado', expirado:'Prazo vencido'};
let user, area, content, page = 'cargas', view = 0, shared = null;
const date = value => value ? new Intl.DateTimeFormat('pt-BR', {dateStyle:'short', timeStyle:'short'}).format(new Date(value)) : 'Não informado';
function el(tag, text, cls) { const n = document.createElement(tag); if (text !== undefined) n.textContent = text; if (cls) n.className = cls; return n; }
function button(text, action, cls = 'secondary') { const b = el('button', text, cls); b.type = 'button'; b.addEventListener('click', action); return b; }
function badge(text) { return el('span', text, 'badge'); }
function empty(title, message) { const n = el('section', undefined, 'empty'); n.append(el('h2', title), el('p', message)); return n; }
function heading(title, subtitle) { const n = el('header', undefined, 'heading'); const copy = el('div'); copy.append(el('h1', title), el('p', subtitle, 'muted')); n.append(copy); return n; }
function notice(text) { return el('p', text, 'notice'); }
function values(items) { const n = el('dl', undefined, 'key-values'); for (const [key, value] of items) n.append(el('dt', key), el('dd', value ?? 'Não informado')); return n; }
function table(headers, rows) {
  const wrap = el('div', undefined, 'table-wrap'), t = el('table'), head = el('thead'), tr = el('tr'), body = el('tbody');
  headers.forEach(x => { const h = el('th', x); h.scope = 'col'; tr.append(h); }); head.append(tr);
  rows.forEach(row => { const r = el('tr'); row.forEach(value => { const cell = el('td'); cell.append(value instanceof Node ? value : document.createTextNode(String(value ?? '—'))); r.append(cell); }); body.append(r); });
  t.append(head, body); wrap.append(t); return wrap;
}
function json(value) { return el('pre', JSON.stringify(value, null, 2), 'json'); }
function field(label, type, name, autocomplete) { const l = el('label', label, 'field'), i = el('input'); i.type=type; i.name=name; i.required=true; i.autocomplete=autocomplete; l.append(i); return [l,i]; }
function showError(error, target) {
  if (error.status === 401 && user) { login('Sua sessão terminou. Entre novamente.'); return; }
  const msg = el('p', error.message || 'Não foi possível carregar.', 'error'); msg.setAttribute('role','alert'); target.append(msg);
}

function login(message = '') {
  view++; user = null;
  root.innerHTML = `<main class="login-layout" id="content"><section class="login-story"><div><span class="brand">Thermo<span>Trace</span></span><div class="tagline">COLD CHAIN INTELLIGENCE</div></div><div><h1>Cada carga.<br>Cada coleta.<br>Um histórico.</h1><p>Acompanhe suas remessas e consulte as evidências de transporte em um só lugar.</p><div class="journey"><div><strong>01</strong>Identificar a carga</div><div><strong>02</strong>Acompanhar coletas</div><div><strong>03</strong>Conferir evidências</div></div></div><p class="hint">Portal de homologação · acesso restrito</p></section><section class="login-panel"><div class="login-card"><p class="eyebrow">Acesso ao portal</p><h2>Entre na sua conta</h2><p class="muted">Use o acesso da sua empresa. Sua área será aberta conforme o cadastro.</p><form id="login-form"></form><p class="hint">Ainda não tem acesso? Solicite o cadastro ao responsável pela ThermoTrace.</p><p class="hint">Ao atualizar ou fechar esta página, será necessário entrar novamente.</p></div></section></main>`;
  const form = document.querySelector('#login-form'), [emailLabel,email] = field('E-mail','email','email','username'), [passwordLabel,password] = field('Senha','password','senha','current-password');
  email.maxLength = 254; password.maxLength = 256;
  const error = el('p', message, 'error'); error.setAttribute('role','alert');
  const submit = el('button','Entrar','primary wide'); submit.type='submit';
  // Campo do app autenticador aparece só quando o servidor pede, depois da senha correta.
  const [mfaLabel,mfa] = field('Código do app autenticador (6 dígitos)','text','codigo_mfa','one-time-code');
  mfa.inputMode='numeric'; mfa.maxLength=12; mfa.required=false; mfaLabel.hidden=true;
  form.append(emailLabel,passwordLabel,mfaLabel,error,submit,button('Tenho um código de acesso',()=>activation(email.value.trim()),'link-button'));
  form.addEventListener('submit', async e => {
    e.preventDefault(); error.textContent=''; submit.disabled=true; submit.textContent='Entrando…';
    try {
      const signed = await api.login(email.value.trim(), password.value, mfaLabel.hidden ? '' : mfa.value.trim());
      password.value=''; mfa.value='';
      const nextArea = audience(signed);
      if (!nextArea) { await api.logout(); throw new Error('Este cadastro ainda não tem uma área disponível. Fale com o responsável pela ThermoTrace.'); }
      user=signed; area=nextArea; shell();
    } catch (err) {
      if (err.codigo==='mfa_necessario' && mfaLabel.hidden) { mfaLabel.hidden=false; mfa.required=true; error.textContent=err.message; mfa.focus(); return; }
      password.value=''; mfa.value=''; error.textContent=err.message;
    }
    finally { submit.disabled=false; submit.textContent='Entrar'; }
  });
}

function activation(prefill='') {
  // Primeiro acesso ou redefinição. O código é digitado; nunca vem por link.
  const card=document.querySelector('.login-card'); card.replaceChildren();
  const form=el('form'), [emailLabel,email]=field('E-mail','email','email','username');
  const [codeLabel,code]=field('Código de acesso','text','codigo','one-time-code'); code.maxLength=32; code.placeholder='XXXX-XXXX-XXXX'; code.autocapitalize='characters'; code.spellcheck=false;
  const [passLabel,pass]=field('Nova senha (mínimo 12 caracteres)','password','nova_senha','new-password'); pass.minLength=12; pass.maxLength=256;
  const [confLabel,conf]=field('Repita a nova senha','password','confirmacao','new-password'); conf.minLength=12; conf.maxLength=256;
  email.value=prefill; email.maxLength=254;
  const error=el('p','','error'); error.setAttribute('role','alert');
  const ok=el('button','Criar senha','primary wide'); ok.type='submit';
  form.append(emailLabel,codeLabel,passLabel,confLabel,error,ok,button('Voltar para entrar',()=>login(),'link-button'));
  card.append(el('p','Primeiro acesso ou nova senha','eyebrow'),el('h2','Use seu código de acesso'),
    el('p','Quem cadastrou seu acesso (o gestor da sua empresa ou a equipe ThermoTrace) entrega o código. Ele vale uma única vez.','muted'),form);
  form.addEventListener('submit',async e=>{
    e.preventDefault(); error.textContent='';
    if(pass.value!==conf.value){ error.textContent='As duas senhas não são iguais.'; return; }
    ok.disabled=true; ok.textContent='Criando…';
    try { await api.ativar(email.value.trim(),code.value.trim(),pass.value); pass.value=conf.value=code.value=''; login('Senha criada. Entre com o e-mail e a nova senha.'); }
    catch(err){ pass.value=conf.value=''; error.textContent=err.message; }
    finally{ ok.disabled=false; ok.textContent='Criar senha'; }
  });
  email.value ? code.focus() : email.focus();
}

function codeCard(nome, acesso) {
  // Mostrado uma única vez; o servidor guarda só o hash.
  const box=el('section',undefined,'card'); box.setAttribute('role','status');
  const code=el('p',acesso.codigo_acesso,'access-code');
  box.append(el('h2',`Código de acesso de ${nome}`),code,
    el('p',`Vale uma única vez, até ${date(acesso.expira_em)}. A pessoa abre o portal, escolhe "Tenho um código de acesso" e cria a própria senha.`,'muted'),
    notice('Anote agora: o código não será mostrado de novo. Entregue pessoalmente ou por telefone, não junto do e-mail. Se perder, gere outro; o anterior deixa de valer.'),
    button('Já entreguei, ocultar',()=>box.remove()));
  return box;
}

function inviteForm(target, onDone) {
  const form=el('form',undefined,'search'), [nl,nome]=field('Nome','text','nome','off'), [el2,email]=field('E-mail','email','email','off');
  const pl=el('label','Permissão','field'), papel=el('select'); papel.name='papel';
  [['operador','Operador'],['gestor','Gestor']].forEach(([v,t])=>{const o=el('option',t);o.value=v;papel.append(o);}); pl.append(papel);
  const ok=el('button','Convidar','primary'); ok.type='submit'; const msg=el('div');
  form.append(nl,el2,pl,ok);
  form.addEventListener('submit',async e=>{
    e.preventDefault(); msg.replaceChildren(); ok.disabled=true;
    try { const r=await api.request('/usuarios/convites',{method:'POST',body:{nome:nome.value.trim(),email:email.value.trim(),papel:papel.value}});
      target.prepend(codeCard(r.usuario.nome,r.acesso)); nome.value=email.value=''; onDone(); }
    catch(error){ showError(error,msg); } finally { ok.disabled=false; }
  });
  const wrap=el('div'); wrap.append(form,msg); return wrap;
}

function companyForm(target, onDone) {
  const form=el('form',undefined,'search'), [rl,razao]=field('Razão social','text','razao','off'), [cl,cnpj]=field('CNPJ','text','cnpj','off');
  const tl=el('label','Atividade','field'), tipo=el('select'); tipo.name='tipo';
  [['embarcador','Cliente · dono da carga'],['transportadora','Contratante · transportadora'],['ambos','Cliente e contratante']].forEach(([v,t])=>{const o=el('option',t);o.value=v;tipo.append(o);}); tl.append(tipo);
  const [gl,gnome]=field('Nome do gestor','text','gestor_nome','off'), [ml,gemail]=field('E-mail do gestor','email','gestor_email','off');
  cnpj.maxLength=18; const ok=el('button','Cadastrar empresa','primary'); ok.type='submit'; const msg=el('div');
  form.append(rl,cl,tl,gl,ml,ok);
  form.addEventListener('submit',async e=>{
    e.preventDefault(); msg.replaceChildren(); ok.disabled=true;
    try { const r=await api.request('/plataforma/clientes',{method:'POST',body:{razao_social:razao.value.trim(),cnpj:cnpj.value.trim(),tipo:tipo.value,gestor:{nome:gnome.value.trim(),email:gemail.value.trim()}}});
      target.prepend(codeCard(`${r.gestor.nome} (${r.razao_social})`,r.acesso)); [razao,cnpj,gnome,gemail].forEach(i=>i.value=''); onDone(); }
    catch(error){ showError(error,msg); } finally { ok.disabled=false; }
  });
  const wrap=el('div'); wrap.append(form,msg); return wrap;
}

function rowAction(label, path, codes, done, nome, confirmText) {
  return button(label, async e=>{
    if(confirmText && !globalThis.confirm(confirmText)) return;
    const b=e.currentTarget; b.disabled=true;
    try { const r=await api.request(path,{method:'POST'}); if(r?.codigo_acesso) codes.prepend(codeCard(nome,r)); done(); }
    catch(error){ b.disabled=false; showError(error,codes); }
  },'link-button');
}

const accessLabel={ativo:'Ativo',aguardando_primeiro_acesso:'Aguardando primeiro acesso',desativado:'Desativado'};
const docTypes=[['nfe','NF-e'],['cte','CT-e'],['pedido','Pedido'],['nfce','NFC-e'],['cte_os','CT-e OS'],['mdfe','MDF-e'],['awb','AWB'],['outro','Outro']];

function opt(select, items) { items.forEach(([v,t])=>{ const o=el('option',t); o.value=v; select.append(o); }); return select; }
function input(label, name, {type='text', required=true, max, min, step, placeholder, value}={}) {
  const l=el('label',label,'field'), i=el('input'); i.type=type; i.name=name; i.required=required; i.autocomplete='off';
  if(max!==undefined) i[type==='number'?'max':'maxLength']=max; if(min!==undefined) i.min=min; if(step) i.step=step;
  if(placeholder) i.placeholder=placeholder; if(value!==undefined) i.value=value; l.append(i); return [l,i];
}
function selectField(label, name, items) { const l=el('label',label,'field'), s=el('select'); s.name=name; opt(s,items); l.append(s); return [l,s]; }
function submitForm(form, ok, msg, run) {
  form.addEventListener('submit',async e=>{ e.preventDefault(); msg.replaceChildren(); ok.disabled=true;
    try { await run(); } catch(error){ showError(error,msg); } finally { ok.disabled=false; } });
}

function newCargoForm(onCreated) {
  // Carga nasce com o critério do produto congelado; o Android bipa sobre ela depois.
  const wrap=el('div'), form=el('form',undefined,'stack'), msg=el('div');
  const [pl,produto]=selectField('Produto','produto',[]);
  const [cl,codigo]=input('Código da carga (opcional)','codigo',{required:false,max:64,placeholder:'Gerado automaticamente se vazio'});
  const [dl,dest]=input('Destinatário','destinatario',{max:240});
  const [sl,desc]=input('Descrição (opcional)','descricao',{required:false,max:1000});
  const [vl,vol]=input('Quantidade de volumes','volumes',{type:'number',min:1,max:200,value:1});
  const [tl,tipo]=selectField('Documento','tipo_doc',docTypes);
  const [nl,num]=input('Número do documento (opcional se houver código)','numero',{required:false,max:64});
  const [kl,chave]=input('Chave de acesso de 44 dígitos (opcional)','chave',{required:false,max:44});
  const ok=el('button','Cadastrar carga','primary'); ok.type='submit';
  form.append(pl,cl,dl,sl,vl,tl,nl,kl,ok); wrap.append(form,msg);
  api.request('/produtos?limite=100').then(rows=>{
    const disp=rows.filter(p=>p.disponivel_para_nova_carga);
    if(!disp.length){ produto.disabled=ok.disabled=true; msg.append(el('p',user.papel==='gestor'?'Nenhum produto disponível. Configure um em "Produtos e perfis".':'Nenhum produto disponível. Peça ao gestor para configurar um produto.','muted')); }
    opt(produto,disp.map(p=>[p.id,`${p.codigo} · ${p.produto||''} · ${p.min_c} a ${p.max_c} °C`]));
  }).catch(error=>showError(error,msg));
  submitForm(form,ok,msg,async()=>{
    const documentos=num.value.trim()?[{tipo:tipo.value,numero:num.value.trim(),...(chave.value.trim()?{chave_acesso:chave.value.trim()}:{})}]:[];
    const r=await api.request('/remessas',{method:'POST',body:{produto_configuracao_id:produto.value,
      ...(codigo.value.trim()?{codigo:codigo.value.trim()}:{}),destinatario_nome:dest.value.trim(),
      ...(desc.value.trim()?{descricao_carga:desc.value.trim()}:{}),quantidade_volumes:Number(vol.value),documentos}});
    onCreated(r);
  });
  return wrap;
}

async function catalogPage(target, ticket) {
  const perfis=el('section',undefined,'card result'), produtos=el('section',undefined,'card result');
  target.append(perfis,produtos);
  const loadPerfis=async()=>{
    const rows=await api.request('/perfis?somente_ativos=false&limite=100'); if(ticket!==view) return rows;
    const list=el('div'), status=p=>p.aprovado_em?(p.ativo?'Aprovado':'Retirado de uso'):'Rascunho';
    list.append(rows.length?table(['Perfil','Versão','Faixa','Situação','Ações'],rows.map(p=>{
      const acts=el('div',undefined,'controls'), m=el('div');
      if(!p.aprovado_em){
        const f=el('form',undefined,'search'), [l,decl]=input('Declaração do responsável (mín. 20 caracteres)','declaracao',{max:2000}); decl.minLength=20;
        const b=el('button','Confirmar aprovação','primary'); b.type='submit'; f.append(l,b); f.hidden=true;
        submitForm(f,b,m,async()=>{ await api.request(`/perfis/${p.id}/aprovar`,{method:'POST',body:{declaracao_responsavel:decl.value.trim()}}); await refresh(); });
        acts.append(button('Aprovar',()=>{f.hidden=false;decl.focus();},'link-button'),f);
      } else if(p.ativo) acts.append(rowAction('Retirar de uso',`/perfis/${p.id}/retirar`,m,()=>refresh(),p.rotulo,`Retirar ${p.codigo} v${p.versao}? Cargas novas deixam de usá-lo; as existentes mantêm o critério congelado.`));
      acts.append(m);
      return [`${p.codigo} · ${p.rotulo}`,p.versao,`${p.min_c} a ${p.max_c} °C`,badge(status(p)),acts];
    })):el('p','Nenhum perfil cadastrado.','muted'));
    return {rows,list};
  };
  const loadProdutos=async()=>api.request('/produtos?limite=100');
  let perfisList=el('div'), produtosList=el('div');
  const refresh=async()=>{
    try {
      const [{rows:ps,list},prods]=await Promise.all([loadPerfis(),loadProdutos()]); if(ticket!==view) return;
      perfisList.replaceChildren(list);
      aprovados.replaceChildren(); opt(aprovados,ps.filter(p=>p.ativo&&p.aprovado_em).map(p=>[p.id,`${p.codigo} v${p.versao} · ${p.min_c} a ${p.max_c} °C`]));
      produtosAtuais=Object.fromEntries(prods.map(p=>[p.codigo,p.versao]));
      produtosList.replaceChildren(prods.length?table(['Produto','Versão','Faixa','Intervalo','Para carga nova'],prods.map(p=>[`${p.codigo}${p.produto?' · '+p.produto:''}`,p.versao,`${p.min_c} a ${p.max_c} °C`,`${p.intervalo_segundos} s`,badge(p.disponivel_para_nova_carga?'Disponível':'Indisponível')])):el('p','Nenhum produto configurado.','muted'));
    } catch(error){ perfisList.replaceChildren(); showError(error,perfisList); }
  };

  // Perfil: rascunho com evidência; modelo só preenche campos, nunca aprova.
  perfis.append(el('h2','Perfis térmicos'),el('p','O perfil define a faixa de temperatura e a fonte que a justifica. Só um perfil aprovado pelo gestor pode ser usado em produto. Faixa e evidência de um perfil aprovado não mudam: para alterar, crie nova versão com o mesmo código.','muted'),perfisList);
  const pd=el('details'), pf=el('form',undefined,'stack'), pm=el('div');
  const [ml,modelo]=selectField('Partir de um modelo de referência (opcional)','modelo',[['','Preencher do zero']]);
  const [cl,cod]=input('Código','codigo',{max:48,placeholder:'Ex.: VACINA-2-8'}); cod.pattern='[A-Za-z0-9][A-Za-z0-9_-]{1,47}';
  const [rl,rot]=input('Nome','rotulo',{max:160});
  const [nl,mn]=input('Mínimo °C','min_c',{type:'number',min:-200,max:150,step:'0.01'});
  const [xl,mx]=input('Máximo °C','max_c',{type:'number',min:-200,max:150,step:'0.01'});
  const [prl,prod]=input('Produto','produto',{max:240}), [al,apr]=input('Apresentação','apresentacao',{max:240}), [col,cond]=input('Condição','condicao',{max:500});
  const [ul,url]=input('Fonte (endereço)','fonte_url',{type:'url'}), [ftl,ft]=input('Título da fonte','fonte_titulo',{max:500}), [fvl,fv]=input('Versão da fonte','fonte_versao',{max:120});
  const [vdl,vd]=input('Fonte conferida em','verificada_em',{type:'date'}); vd.max=new Date().toISOString().slice(0,10);
  const [jl,just]=input('Justificativa (mín. 10 caracteres)','justificativa',{max:2000}); just.minLength=10;
  const pok=el('button','Salvar rascunho','primary'); pok.type='submit';
  pf.append(ml,cl,rl,nl,xl,prl,al,col,ul,ftl,fvl,vdl,jl,pok);
  pd.append(el('summary','Novo perfil (rascunho)'),notice('Modelos de referência são pontos de partida pesquisados, não aprovação sanitária. Confira a bula ou norma do produto antes de aprovar.'),pf,pm);
  perfis.append(pd);
  api.request('/perfis/modelos').then(ref=>{
    const usaveis=(ref.profiles||[]).filter(p=>p.temperature_c&&p.temperature_c.min!=null&&p.temperature_c.max!=null);
    opt(modelo,usaveis.map(p=>[p.id,`${p.name} (${p.temperature_c.min} a ${p.temperature_c.max} °C)`]));
    modelo.addEventListener('change',()=>{ const p=usaveis.find(x=>x.id===modelo.value); if(!p) return;
      cod.value=p.id.replace(/[^A-Z0-9_-]/gi,'-').slice(0,48).toUpperCase(); rot.value=p.name; mn.value=p.temperature_c.min; mx.value=p.temperature_c.max;
      cond.value=p.condition||''; const s=(p.sources||[])[0]; if(s){ url.value=s.url||''; ft.value=s.id||''; } just.value=p.requirements||''; });
  }).catch(()=>{});
  submitForm(pf,pok,pm,async()=>{
    await api.request('/perfis',{method:'POST',body:{codigo:cod.value.trim().toUpperCase(),rotulo:rot.value.trim(),min_c:mn.value,max_c:mx.value,
      evidencia:{produto:prod.value.trim(),apresentacao:apr.value.trim(),condicao:cond.value.trim(),fonte_url:url.value.trim(),fonte_titulo:ft.value.trim(),
                 fonte_versao:fv.value.trim(),verificada_em:vd.value,justificativa:just.value.trim()}}});
    pf.reset(); pd.open=false; await refresh();
  });

  // Produto: liga um código de produto a um perfil aprovado e ao intervalo de medição.
  produtos.append(el('h2','Produtos'),el('p','O produto liga um código ao perfil aprovado e ao intervalo de medição. Reconfigurar um código cria nova versão; cargas já criadas mantêm o critério anterior.','muted'),produtosList);
  let produtosAtuais={};
  const qd=el('details'), qf=el('form',undefined,'stack'), qm=el('div');
  const [qcl,qcod]=input('Código do produto','codigo',{max:64,placeholder:'Ex.: SKU-VACINA-01'});
  const [qpl,aprovados]=selectField('Perfil aprovado','perfil',[]);
  const [qil,intervalo]=input('Intervalo entre medições (segundos)','intervalo',{type:'number',min:1,max:86400,value:600});
  const qok=el('button','Configurar produto','primary'); qok.type='submit'; qf.append(qcl,qpl,qil,qok);
  qd.append(el('summary','Configurar produto'),qf,qm); produtos.append(qd);
  submitForm(qf,qok,qm,async()=>{
    const codigo=qcod.value.trim().toUpperCase();
    await api.request('/produtos',{method:'POST',body:{codigo,perfil_termico_id:aprovados.value,intervalo_segundos:Number(intervalo.value),
      ...(produtosAtuais[codigo]?{versao_anterior:produtosAtuais[codigo]}:{})}});
    qf.reset(); intervalo.value=600; qd.open=false; await refresh();
  });
  await refresh();
}

function shell() {
  root.innerHTML = `<div class="shell"><aside class="sidebar"><div><span class="brand">Thermo<span>Trace</span></span><div class="tagline">COLD CHAIN INTELLIGENCE</div></div><nav aria-label="Navegação principal"></nav><div class="sidebar-bottom">Homologação<br>Histórico com origem e autoria.<br>Acesso limitado à sua permissão.</div></aside><div class="main"><header class="topbar"><span id="area" class="eyebrow"></span><div class="top-actions"><div class="identity" id="identity"></div><div id="exit"></div></div></header><main id="content" class="content" tabindex="-1" aria-live="polite"></main></div></div>`;
  document.querySelector('#area').textContent=labels[area];
  document.querySelector('#identity').append(el('strong',user.nome),el('span',user.empresa));
  document.querySelector('#exit').append(button('Sair', async e => {
    e.currentTarget.disabled=true; view++;
    // Remove imediatamente qualquer dado exibido durante a revogação.
    content.replaceChildren(el('p','Encerrando sua sessão…','loading'));
    let message=''; try { await api.logout(); } catch { message='Você saiu deste navegador. Não foi possível confirmar a revogação no servidor.'; }
    login(message);
  }));
  const nav=document.querySelector('nav');
  const recebe = area==='contratante' || area==='ambos';
  const items=[...(area==='staff' ? [['empresas','Empresas cadastradas']] : [['cargas','Minhas cargas'], ['acompanhamento','Acompanhamento'], ...(recebe ? [['recebidas','Compartilhadas comigo']] : []), ...(user.papel==='gestor' ? [['catalogo','Produtos e perfis'],['equipe','Equipe da empresa'],['integridade','Integridade'],['auditoria','Auditoria']] : [])]), ['seguranca','Segurança da conta']];
  items.forEach(([key,label]) => { const b=button(label,()=>navigate(key),'nav-button'); b.dataset.page=key; nav.append(b); });
  // Equipe ThermoTrace sem MFA cai direto na ativação: o servidor recusa a área dela até lá.
  content=document.querySelector('#content'); navigate(user.mfa_obrigatorio && !user.mfa_ativo ? 'seguranca' : items[0][0]);
}

async function navigate(key, offset=0, query='') {
  page=key; const ticket=++view;
  document.querySelectorAll('[data-page]').forEach(n => n.setAttribute('aria-current',n.dataset.page===key?'page':'false'));
  const titles={cargas:['Minhas cargas',area==='contratante'?'Consulte as cargas cadastradas na sua transportadora.':'Localize a carga e acompanhe os registros recebidos.'],recebidas:['Compartilhadas comigo','Cargas que outras empresas autorizaram sua empresa a consultar.'],catalogo:['Produtos e perfis','Critérios térmicos aprovados pela sua empresa e os produtos que os usam.'],seguranca:['Segurança da conta','Verificação em duas etapas com app autenticador.'],empresas:['Empresas cadastradas','Visão administrativa da equipe ThermoTrace.'],equipe:['Equipe da empresa','Pessoas com acesso ao ambiente da sua empresa.'],auditoria:['Auditoria','Eventos registrados pelo servidor, do mais antigo ao mais recente.'],acompanhamento:['Acompanhamento','Etiquetas em campo sem leitura final e celulares que pararam de enviar.'],integridade:['Integridade das evidências','Manifesto para guardar fora da ThermoTrace e conferir depois.']};
  content.replaceChildren(heading(...titles[key]));
  if (key==='cargas') {
    content.append(notice(area==='contratante'
      ? 'Aqui aparecem as cargas cadastradas pela sua empresa. Cargas de outras empresas ficam em "Compartilhadas comigo", depois do aceite do gestor.'
      : 'Aqui aparecem as cargas cadastradas pela sua empresa. O gestor pode compartilhar uma carga com a transportadora na página da carga; nenhuma outra carga fica visível para ela.'));
    const form=el('form',undefined,'search'), l=el('label','Código da carga ou documento fiscal','field'), input=el('input');
    input.name='busca'; input.value=query; input.maxLength=96; input.placeholder='Ex.: REM-2026-00184 ou chave da NF-e'; l.append(input);
    const search=el('button','Buscar','primary'); search.type='submit';
    form.append(l,search,button('Limpar',()=>navigate(key))); form.addEventListener('submit',e=>{e.preventDefault(); navigate(key,0,input.value.trim());}); content.append(form);
    if(!query){ const d=el('details'); d.append(el('summary','Nova carga'),newCargoForm(r=>detail(r.id))); content.append(d); }
  }
  if (key==='catalogo') { try { await catalogPage(content,ticket); } catch(error){ if(ticket===view) showError(error,content); } return; }
  if (key==='seguranca') { securityPage(content); return; }
  if (key==='integridade') { integrityPage(content,ticket); return; }
  if (key==='acompanhamento') { try { await monitorPage(content,ticket); } catch(error){ if(ticket===view) showError(error,content); } return; }
  // Atualiza só a lista: o cartão com o código recém-gerado precisa continuar na tela.
  const codes=el('div',undefined,'stack'); let reload=()=>{};
  if (key==='empresas') { content.append(notice('O acesso de suporte ao conteúdo das cargas exigirá autorização e registro de auditoria. Esta área mostra os cadastros das empresas e permite entregar o acesso do gestor.'),codes); const d=el('details'); d.append(el('summary','Cadastrar empresa'),companyForm(codes,()=>reload())); content.append(d); }
  if (key==='equipe') { content.append(codes); const d=el('details'); d.append(el('summary','Convidar pessoa'),inviteForm(codes,()=>reload())); content.append(d); }
  if (key==='recebidas') content.append(notice(user.papel==='gestor'
    ? 'Aceite apenas convites que você reconhece. Depois do aceite, a equipe da sua empresa consulta somente esta carga; o dono pode encerrar o acesso a qualquer momento e vê cada consulta.'
    : 'Somente cargas com compartilhamento aceito pelo gestor da sua empresa aparecem aqui.'));
  const results=el('section'); results.setAttribute('aria-label','Resultados'); results.append(el('p','Carregando…','loading')); content.append(results);
  reload=async()=>{ try {
    const path= key==='recebidas'?`/compartilhamentos/recebidos?situacao=pendente&situacao=ativo&limite=20&offset=${offset}`:key==='empresas'?`/plataforma/clientes?limite=20&offset=${offset}`:key==='equipe'?`/usuarios?limite=20&offset=${offset}`:key==='auditoria'?`/auditoria?limite=20&depois_id=${offset}`:query?`/remessas/localizar?valor=${encodeURIComponent(query)}&limite=20&offset=${offset}`:`/remessas?limite=20&offset=${offset}`;
    const data=await api.request(path); if (ticket!==view) return;
    const rows=query&&key==='cargas'?data.candidatas:data; results.replaceChildren();
    if (!rows.length) results.append(empty('Nenhum registro nesta consulta',key==='cargas'?'Confira o código ou documento. Cargas e leituras que ainda estão só no celular não aparecem aqui.':key==='recebidas'?'Nenhuma empresa compartilhou uma carga com a sua, ou o acesso foi encerrado.':'Não há registros disponíveis nesta página.'));
    else if (key==='recebidas') results.append(table(['Carga','Dono da carga','Situação','Permissão','Prazo','Ações'],rows.map(c=>[
      c.situacao==='ativo'?button(c.remessa_codigo,()=>detail(c.remessa_id,c),'link-button'):c.remessa_codigo,
      c.empresa_dona,badge(shareStatus[c.situacao]||c.situacao),sharePermission(c),
      c.expira_em?date(c.expira_em):'Até ser encerrado',shareActions(c,()=>navigate('recebidas'))])));
    else if (key==='cargas') results.append(table(['Carga','Situação','Destinatário','Criada em'],rows.map(r=>[button(r.codigo,()=>detail(r.id),'link-button'),badge(cargoStatus(r)),r.destinatario_nome,date(r.criado_em)])));
    else if (key==='empresas') results.append(table(['Empresa','CNPJ','Atividade','Cadastro','Gestores'],rows.map(r=>{
      const gs=el('div',undefined,'stack');
      (r.gestores||[]).forEach(g=>{ const line=el('div'); line.append(el('span',`${g.nome} · ${accessLabel[g.acesso]||g.acesso} `),
        ...(g.ativo?[rowAction(g.acesso==='ativo'?'Novo código (senha esquecida)':'Gerar novo convite',`/plataforma/clientes/${r.id}/gestores/${g.id}/codigo-acesso`,codes,()=>reload(),`${g.nome} (${r.razao_social})`,
          g.acesso==='ativo'?`Gerar um código para ${g.nome} criar nova senha? A ação aparece na auditoria da empresa.`:null)]:[])); gs.append(line); });
      return [r.razao_social,r.cnpj,labels[{embarcador:'cliente',transportadora:'contratante',ambos:'ambos'}[r.tipo]]||r.tipo,badge(r.ativa?'Ativo':'Inativo'),gs];
    })));
    else if (key==='equipe') results.append(table(['Pessoa','E-mail','Permissão','Acesso','Ações'],rows.map(r=>{
      const acts=el('div',undefined,'controls'), propria=r.id===user.id;
      if(r.ativo && !propria && (r.papel==='operador' || r.acesso==='aguardando_primeiro_acesso'))
        acts.append(rowAction(r.acesso==='ativo'?'Novo código (senha esquecida)':'Gerar novo convite',`/usuarios/${r.id}/codigo-acesso`,codes,()=>reload(),r.nome));
      if(r.ativo && !propria && r.papel==='operador')
        acts.append(rowAction('Desativar',`/usuarios/${r.id}/desativar`,codes,()=>reload(),r.nome,`Desativar ${r.nome}? As sessões abertas são encerradas na hora.`));
      return [r.nome,r.email,r.papel==='gestor'?'Gestor':'Operador',badge(accessLabel[r.acesso]||(r.ativo?'Ativo':'Desativado')),acts];
    })));
    else results.append(table(['Registro','Data e hora','Ação','Entidade','Responsável'],rows.map(r=>[String(r.id),date(r.em),r.acao,r.entidade,r.usuario_id||'Sistema'])));
    const pager=el('div',undefined,'pager'), controls=el('div',undefined,'controls');
    pager.append(el('span',`${rows.length} registro(s) nesta página`));
    if(offset>0) controls.append(button(key==='auditoria'?'Voltar ao início':'Anterior',()=>navigate(key,key==='auditoria'?0:Math.max(0,offset-20),query)));
    const more=query&&key==='cargas'?data.ha_mais:rows.length===20;
    if(more) controls.append(button('Próxima',()=>navigate(key,key==='auditoria'?rows.at(-1).id:offset+20,query)));
    pager.append(controls); results.append(pager);
  } catch(error) { if(ticket===view) { results.replaceChildren(); showError(error,results); } } };
  await reload();
}

const monitorStatus={registro_encerrado:'Memória programada acabou',sem_coleta_recente:'Sem coleta recente',em_andamento:'Em andamento'};
const hours=s=>s==null?'—':s<3600?`${Math.round(s/60)} min`:`${(s/3600).toLocaleString('pt-BR',{maximumFractionDigits:1})} h`;
function monitorCargo(s) {
  const link=button(s.remessa_codigo,()=>detail(s.remessa_id),'link-button');
  if(s.acesso!=='compartilhada') return link;
  const box=el('div'); box.append(link,el('div',`Carga de ${s.empresa_dona||'outra empresa'}`,'muted')); return box;
}
async function monitorPage(target, ticket, horas=24) {
  // Só o que já chegou ao servidor; coletas ainda no celular não aparecem aqui.
  const box=el('section',undefined,'stack'); target.append(box); box.append(el('p','Carregando…','loading'));
  const p=await api.request(`/acompanhamento?horas_sem_coleta=${horas}`); if(ticket!==view) return;
  const t=p.totais; box.replaceChildren();
  box.append(notice('Mostra apenas o que já chegou ao servidor. Quando a memória programada da etiqueta acaba, ela para de registrar: faça a leitura final. Um celular com atraso grande de envio pode estar sem rede ou com a fila parada.'));
  const filtro=el('form',undefined,'search'), [l,i]=input('Alertar sem coleta há mais de (horas)','horas',{type:'number',min:1,max:720,value:String(horas)});
  const ok=el('button','Atualizar','secondary'); ok.type='submit'; filtro.append(l,ok);
  filtro.addEventListener('submit',e=>{e.preventDefault(); const n=Number(i.value)||24; target.replaceChildren(target.firstChild); monitorPage(target,++view,n);});
  box.append(filtro,values([['Sessões sem leitura final',String(t.sessoes_abertas)],['Memória programada acabou',String(t.registro_encerrado)],[`Sem coleta há mais de ${horas} h`,String(t.sem_coleta_recente)],['Consultado em',date(p.gerado_em)]]));
  const sess=el('section',undefined,'card result'); sess.append(el('h2','Etiquetas sem leitura final'));
  sess.append(p.sessoes_abertas.length?table(['Situação','Carga','Volume','Início','Última coleta','Coletas','Fim previsto da memória'],p.sessoes_abertas.map(s=>[
    badge(monitorStatus[s.situacao]||s.situacao),monitorCargo(s),s.volume,date(s.iniciada_em),date(s.ultima_coleta_em),String(s.coletas),
    `${date(s.fim_previsto_memoria)}${s.horas_ate_fim_previsto>0?` (em ${hours(s.horas_ate_fim_previsto*3600)})`:''}`])):el('p','Nenhuma etiqueta aguardando leitura final.','muted'));
  if(p.ha_mais_sessoes) sess.append(el('p','Há mais sessões abertas além das 200 mostradas.','muted'));
  const dev=el('section',undefined,'card result'); dev.append(el('h2','Celulares da empresa'));
  if(p.sessoes_abertas.some(s=>s.acesso==='compartilhada')) sess.append(el('p','Inclui etiquetas em cargas de outras empresas que a sua pode operar. O dono da carga vê que este acompanhamento foi consultado.','muted'));
  dev.append(p.aparelhos.length?table(['Aparelho','Versão do app','Último envio recebido','Coletas em 7 dias','Maior atraso de envio (7 dias)','Último operador','Situação'],p.aparelhos.map(a=>[
    a.modelo||'Sem modelo',a.versao_app||'—',a.ultima_recepcao_em?date(a.ultima_recepcao_em):'Nunca enviou',String(a.coletas_7_dias),hours(a.maior_atraso_envio_7_dias_s),a.ultimo_operador||'—',badge(a.ativo?'Ativo':'Desativado')])):el('p','Nenhum celular registrado.','muted'));
  box.append(sess,dev,el('p',`Regra ${p.versao}. ${p.limites[1]}`,'muted'));
}

const integrityStatus={divergente:'Diferente do manifesto',cadeia_divergente_depois:'Cadeia alterada depois',sessao_ausente:'Sessão não encontrada'};
function integrityPage(target, ticket) {
  // TT-060: a referência fica com o cliente. O servidor sozinho não prova que não reescreveu a cadeia.
  const emit=el('section',undefined,'card result'), check=el('section',undefined,'card result');
  emit.append(el('h2','1. Baixar manifesto'),el('p','O arquivo lista cada sessão com a quantidade de leituras e o código de integridade (hash) da última. Não contém temperaturas nem dados das cargas. Guarde-o fora da ThermoTrace: e-mail para você mesmo, pasta da qualidade ou impressão. A emissão fica na Auditoria.','muted'));
  const msg1=el('div'), get=button('Baixar manifesto agora',async e=>{
    const b=e.currentTarget; b.disabled=true; msg1.replaceChildren();
    try { const blob=await api.request('/integridade/manifesto',{download:true}); if(ticket!==view)return;
      const m=JSON.parse(await blob.text()), url=URL.createObjectURL(blob), a=document.createElement('a');
      a.href=url; a.download=`ThermoTrace-manifesto-${m.emitido_em.slice(0,10)}-${m.hash_manifesto.slice(0,12)}.json`; a.click(); setTimeout(()=>URL.revokeObjectURL(url),1000);
      msg1.append(notice(`Manifesto emitido em ${date(m.emitido_em)}: ${m.total_sessoes} sessão(ões), ${m.total_leituras} leitura(s). Código do manifesto: ${m.hash_manifesto.slice(0,16)}…`));
    } catch(error){ showError(error,msg1); } finally { b.disabled=false; }
  },'primary');
  emit.append(get,msg1);
  check.append(el('h2','2. Conferir um manifesto guardado'),el('p','Escolha um manifesto baixado antes. O servidor confere se cada sessão ainda tem exatamente o mesmo código naquela posição. Leituras recebidas depois da emissão não atrapalham.','muted'));
  const form=el('form',undefined,'search'), l=el('label','Arquivo do manifesto (.json)','field'), file=el('input'); file.type='file'; file.accept='application/json,.json'; file.required=true; l.append(file);
  const ok=el('button','Conferir','primary'); ok.type='submit'; form.append(l,ok); const msg2=el('div');
  submitForm(form,ok,msg2,async()=>{
    let m; try { m=JSON.parse(await file.files[0].text()); } catch { throw new Error('O arquivo escolhido não é um manifesto em JSON.'); }
    const r=await api.request('/integridade/manifesto/conferir',{method:'POST',body:m}); if(ticket!==view)return;
    msg2.replaceChildren(notice(r.conferencia==='confere'
      ? `Confere: as ${r.sessoes_conferidas} sessão(ões) do manifesto de ${date(r.emitido_em)} não mudaram desde a emissão.`
      : !r.arquivo_intacto ? 'O arquivo do manifesto foi editado depois de emitido. Use a cópia original guardada.'
      : `Atenção: ${r.sessoes_com_problema} sessão(ões) diferem do manifesto. Encaminhe para a qualidade e para a equipe ThermoTrace.`));
    if(r.problemas.length) msg2.append(table(['Sessão','Situação','Leituras no manifesto','Leituras agora'],r.problemas.map(p=>[p.sessao_id||'—',integrityStatus[p.situacao]||p.situacao,p.leituras_no_manifesto??'—',p.leituras_agora??'—'])));
    msg2.append(el('p',r.limites[0],'muted'));
  });
  check.append(form,msg2); target.append(emit,check);
}

function securityPage(target) {
  const card=el('section',undefined,'card result'), msg=el('div');
  target.append(card);
  const render=()=>{
    card.replaceChildren(el('h2','Verificação em duas etapas'));
    if(user.mfa_ativo){
      card.append(el('p','Ativa. Ao entrar, além da senha, o portal pede o código de 6 dígitos do seu app autenticador.','muted'));
      if(!user.mfa_obrigatorio){
        const f=el('form',undefined,'search'), [l,c]=input('Código atual do app para desativar','codigo',{max:12}); c.inputMode='numeric';
        const b=el('button','Desativar','secondary'); b.type='submit'; f.append(l,b); card.append(f,msg);
        submitForm(f,b,msg,async()=>{ await api.request('/auth/mfa/desativar',{method:'POST',body:{codigo:c.value.trim()}}); user.mfa_ativo=false; render(); });
      } else card.append(el('p','Obrigatória para a equipe ThermoTrace. Se perder o celular, peça a redefinição a outra pessoa da equipe com acesso ao servidor.','muted'));
      return;
    }
    if(user.mfa_obrigatorio) card.append(notice('A equipe ThermoTrace precisa ativar a verificação em duas etapas antes de acessar a área de empresas.'));
    card.append(el('p','Instale um app autenticador no celular (Google Authenticator, Microsoft Authenticator ou similar). Depois de ativar, cada entrada pede a senha e um código de 6 dígitos que muda a cada 30 segundos.','muted'));
    const start=button('Ativar verificação em duas etapas',async e=>{
      e.currentTarget.disabled=true; msg.replaceChildren();
      try {
        const r=await api.request('/auth/mfa/iniciar',{method:'POST'});
        const passo=el('div',undefined,'stack'), link=el('a','Abrir no app autenticador (no celular)','link-button'); link.href=r.uri;
        const f=el('form',undefined,'search'), [l,c]=input('Código de 6 dígitos mostrado no app','codigo',{max:12}); c.inputMode='numeric'; c.autocomplete='one-time-code';
        const b=el('button','Confirmar e ativar','primary'); b.type='submit'; f.append(l,b);
        passo.append(el('p','1. No app, escolha adicionar conta e digite esta chave (tipo: baseada em tempo):'),el('p',r.chave,'access-code'),link,
          el('p','2. Digite abaixo o código que o app mostrar.'),f);
        start.replaceWith(passo);
        submitForm(f,b,msg,async()=>{ await api.request('/auth/mfa/confirmar',{method:'POST',body:{codigo:c.value.trim()}});
          user.mfa_ativo=true; msg.replaceChildren(); render(); card.append(notice('Verificação ativada. Outras sessões abertas desta conta foram encerradas.')); });
      } catch(error){ e.currentTarget.disabled=false; showError(error,msg); }
    },'primary');
    card.append(start,msg);
  };
  render();
}

// Uma frase para o que a transportadora pode fazer na carga.
function sharePermission(c) {
  const partes=['Consultar'];
  if(c.pode_exportar) partes.push('exportar');
  if(c.pode_operar) partes.push('operar (bipar)');
  return partes.length===1?'Somente consultar':partes.length===2?partes.join(' e '):`${partes[0]}, ${partes[1]} e ${partes[2]}`;
}

function shareActions(c, done) {
  const box=el('div',undefined,'controls');
  if(user.papel!=='gestor') return box;
  const msg=el('div');
  const run=(path,body)=>async e=>{
    e.currentTarget.disabled=true; msg.replaceChildren();
    try { await api.request(path,{method:'POST',body}); done(); }
    catch(error){ e.currentTarget.disabled=false; showError(error,msg); }
  };
  if(c.minha_parte==='parceira' && c.situacao==='pendente')
    box.append(button('Aceitar',run(`/compartilhamentos/${c.id}/aceitar`,{}),'primary'),button('Recusar',run(`/compartilhamentos/${c.id}/recusar`,{})));
  // Parceira recusa um convite pendente; só encerra o que já aceitou. Dono encerra pendente ou ativo.
  if(c.situacao==='ativo' || (c.situacao==='pendente' && c.minha_parte==='dona')) {
    const form=el('form',undefined,'search'), l=el('label','Motivo do encerramento','field'), input=el('input');
    input.name='motivo'; input.minLength=10; input.maxLength=2000; input.required=true; l.append(input);
    const ok=el('button','Confirmar encerramento','secondary'); ok.type='submit'; form.append(l,ok); form.hidden=true;
    form.addEventListener('submit',e=>{e.preventDefault(); run(`/compartilhamentos/${c.id}/revogar`,{motivo:input.value.trim()})({currentTarget:ok});});
    box.append(button(c.minha_parte==='dona'?'Encerrar acesso':'Sair do compartilhamento',()=>{form.hidden=false;input.focus();}),form);
  }
  box.append(msg); return box;
}

async function sharingCard(r, ticket) {
  const card=el('section',undefined,'card result');
  card.append(el('h2','Compartilhamento com transportadora'),
    el('p','A transportadora só acessa esta carga depois do aceite do gestor dela. Se você permitir operar, os operadores dela iniciam o monitoramento e enviam leituras com etiquetas e celulares da transportadora; documentos e cancelamento continuam só com a sua empresa. Você vê cada consulta e cada leitura na Auditoria e pode encerrar o acesso a qualquer momento.','muted'));
  const list=el('div'), form=el('form',undefined,'share-form'), msg=el('div');
  const [cnpjLabel,cnpj]=field('CNPJ da transportadora','text','cnpj','off'); cnpj.maxLength=18; cnpj.placeholder='Transportadora cadastrada na ThermoTrace';
  const exp=el('label','Permitir baixar CSV, gráfico e relatório','field check'), chk=el('input'); chk.type='checkbox'; chk.name='exportar'; exp.prepend(chk);
  const opr=el('label','Permitir operar a carga (bipar etiquetas e enviar leituras)','field check'), chkOp=el('input'); chkOp.type='checkbox'; chkOp.name='operar'; opr.prepend(chkOp);
  const prazoLabel=el('label','Prazo (opcional)','field'), prazo=el('input'); prazo.type='date'; prazo.name='prazo'; prazoLabel.append(prazo);
  const send=el('button','Compartilhar esta carga','primary'); send.type='submit';
  form.append(cnpjLabel,prazoLabel,exp,opr,send);
  const load=async()=>{
    const rows=await api.request(`/remessas/${r.id}/compartilhamentos`); if(ticket!==view)return;
    list.replaceChildren(rows.length?table(['Transportadora','Situação','Permissão','Prazo','Ações'],rows.map(c=>[c.empresa_parceira,badge(shareStatus[c.situacao]||c.situacao),sharePermission(c),c.expira_em?date(c.expira_em):'Até ser encerrado',shareActions(c,load)])):el('p','Esta carga não foi compartilhada.','muted'));
  };
  form.addEventListener('submit',async e=>{
    e.preventDefault(); msg.replaceChildren(); send.disabled=true;
    // Fim do dia escolhido no horário local, enviado com fuso.
    const body={cnpj_parceira:cnpj.value.trim(),pode_exportar:chk.checked,pode_operar:chkOp.checked,...(prazo.value?{expira_em:new Date(`${prazo.value}T23:59:59`).toISOString()}:{})};
    try { await api.request(`/remessas/${r.id}/compartilhamentos`,{method:'POST',body}); cnpj.value=''; chk.checked=false; chkOp.checked=false; prazo.value=''; await load(); }
    catch(error){ showError(error,msg); } finally { send.disabled=false; }
  });
  card.append(list,form,msg);
  try { await load(); } catch(error) { showError(error,list); }
  return card;
}

async function detail(id, share=null) {
  shared=share;
  const back=()=>share?button('← Voltar às compartilhadas',()=>navigate('recebidas'),'secondary back'):button('← Voltar às cargas',()=>navigate('cargas'),'secondary back');
  const ticket=++view; content.replaceChildren(back(),el('p','Carregando carga…','loading'));
  try {
    const r=await api.request(`/remessas/${id}`); if(ticket!==view)return;
    // Chegou por outro caminho (acompanhamento): o servidor diz se a carga é de outra empresa e o que foi concedido.
    if(!share && r.acesso==='compartilhada') share=shared={empresa_dona:r.empresa_dona||'outra empresa',pode_operar:r.pode_operar,pode_exportar:r.pode_exportar};
    content.replaceChildren(back(),heading(r.codigo,r.descricao_carga||'Detalhes e evidências da carga'));
    if(share) content.append(notice(`Carga de ${share.empresa_dona}, compartilhada com a sua empresa. Permissão: ${sharePermission(share).toLowerCase()}. ${share.pode_operar?'Os operadores da sua empresa iniciam o monitoramento e enviam leituras pelo aplicativo, com etiquetas e celulares da sua empresa. ':''}O dono vê cada consulta${share.pode_operar?' e cada leitura':''} e pode encerrar o acesso a qualquer momento.`));
    content.append(notice('Escolha uma coleta para consultar o gráfico e baixar os registros recebidos. Cada gráfico mostra o histórico daquele bipe; não soma coletas repetidas. A confirmação de parada da etiqueta ainda permanece no aplicativo.'));
    const grid=el('div',undefined,'grid'), summary=el('section',undefined,'card'), docs=el('section',undefined,'card');
    summary.append(el('h2','Resumo da carga'),values([['Situação',cargoStatus(r)],['Destinatário',r.destinatario_nome],['Criada em',date(r.criado_em)],['Intervalo',`${r.intervalo_segundos} segundos`]]));
    if(r.criterio) { const d=el('details');d.append(el('summary','Critério térmico preservado'),json(r.criterio));summary.append(d); }
    docs.append(el('h2','Documentos vinculados'));
    if(!r.documentos.length) docs.append(el('p','Nenhum documento vinculado.','muted'));
    r.documentos.forEach(d=>docs.append(values([['Documento',`${d.tipo} · ${d.numero||'Sem número'}`],['Chave',d.chave_acesso||'Não informada'],['Origem',d.digitado_manualmente?'Digitado manualmente':'Leitura do documento'],['Validação',d.validado?'Validado':'Pendente de conferência']])));
    grid.append(summary,docs); content.append(grid);
    const volumes=el('section',undefined,'card result'); volumes.append(el('h2','Volumes'));
    if(!r.volumes.length)volumes.append(el('p','Nenhum volume cadastrado.','muted'));
    r.volumes.forEach(v=>{ const box=el('div',undefined,'volume'), h=el('div',undefined,'volume-header'), result=el('div',undefined,'result'); const nome=el('div'); nome.append(el('strong',`Volume ${v.sequencia}`),document.createTextNode(' '),badge(volumeStatus[v.status]||v.status)); if(v.identidade) nome.append(el('div',v.identidade,'muted')); h.append(nome); if(v.monitorado) h.append(button('Consultar coletas',e=>sessions(v.id,result,e.currentTarget,ticket))); else h.append(el('span','Aguardando ativação da etiqueta pelo aplicativo','muted')); box.append(h,result);volumes.append(box); });
    content.append(volumes);
    if(!share && user.papel==='gestor' && r.empresa_id===user.empresa_id) { const card=await sharingCard(r,ticket); if(ticket===view) content.append(card); }
  } catch(error) { if(ticket===view){ content.querySelector('.loading')?.remove(); showError(error,content); } }
}

async function action(target, trigger, ticket, load) {
  trigger.disabled=true; target.replaceChildren(el('p','Consultando…','loading'));
  try { const result=await load(); if(ticket===view)target.replaceChildren(result); }
  catch(error){if(ticket===view){target.replaceChildren();showError(error,target);}}
  finally{trigger.disabled=false;}
}

function sessions(id,target,trigger,ticket,offset=0) {
  return action(target,trigger,ticket,async()=>{
    const rows=await api.request(`/volumes/${id}/sessoes?limite=20&offset=${offset}`), list=el('div',undefined,'stack');
    if(!rows.length)list.append(empty('Sem sessões nesta página','Só aparecem as evidências que já chegaram ao servidor.'));
    for(const s of rows){
      const box=el('section',undefined,'card');box.append(el('h3',`Sessão · ${sessionStatus[s.estado_logico]||s.estado_logico}`),values([['Identificação',s.id],['Origem',s.origem==='simulacao'?'Simulação':'Declaração do Android'],['Faixa configurada',`${s.min_configurado_c} a ${s.max_configurado_c} °C`],['Intervalo',`${s.intervalo_segundos} segundos`]]));
      const readings=el('div',undefined,'result'), integrity=el('div',undefined,'result');
      box.append(button('Viagem consolidada',e=>trip(s.id,readings,e.currentTarget,ticket),'primary'),button('Ver comprovantes',e=>receipts(s.id,readings,e.currentTarget,ticket)),button('Verificar integridade',e=>action(integrity,e.currentTarget,ticket,async()=>{
        const data=await api.request(`/sessoes/${s.id}/integridade`); const panel=el('div'); panel.append(notice(data.integra?'Cadeia de evidências íntegra. Isso não certifica a temperatura nem o sensor.':'A cadeia de evidências apresenta divergência. Encaminhe para conferência.'),json(data));return panel;
      }),'link-button'),readings,integrity);list.append(box);
    }
    if(offset>0)list.append(button('Sessões anteriores',e=>sessions(id,target,e.currentTarget,ticket,Math.max(0,offset-20))));
    if(rows.length===20)list.append(button('Mais sessões',e=>sessions(id,target,e.currentTarget,ticket,offset+20)));
    return list;
  });
}

function receipts(id,target,trigger,ticket,after=0){
  return action(target,trigger,ticket,async()=>{
    const rows=await api.request(`/sessoes/${id}/leituras?limite=20&apos_ordem=${after}`), panel=el('div'), evidence=el('div',undefined,'result');
    if(!rows.length) panel.append(el('p','Nenhum comprovante nesta página.','muted'));
    // Evidência bruta fica com o dono da carga; a parceira consulta comprovantes e gráfico.
    else panel.append(table(['Ordem','Coleta','Recebida no servidor','Temperaturas','Evidência'],rows.map(r=>[r.ordem_recebimento,r.tipo,date(r.recebida_em_servidor),button('Gráfico e relatório',e=>temperatures(id,r.leitura_id,evidence,e.currentTarget,ticket),'link-button'),shared?'Com o dono da carga':button('Abrir',e=>action(evidence,e.currentTarget,ticket,async()=>json(await api.request(`/sessoes/${id}/leituras/${r.leitura_id}`))),'link-button')])));
    if(after>0)panel.append(button('Voltar ao início',e=>receipts(id,target,e.currentTarget,ticket)));
    if(rows.length===20)panel.append(button('Próximos comprovantes',e=>receipts(id,target,e.currentTarget,ticket,rows.at(-1).ordem_recebimento)));
    panel.append(evidence);return panel;
  });
}

function trip(session,target,trigger,ticket) {
  // WEB-12: coleta mais completa como base; as demais conferidas, nunca somadas.
  const path=`/sessoes/${session}/viagem`;
  return action(target,trigger,ticket,async()=>{
    const v=await api.request(path), r=v.resumo;
    const base=v.coletas.find(c=>c.leitura_id===v.base_leitura_id);
    const dados={...v,tipo:r?(r.inclui_leitura_final?'Viagem completa':'Viagem parcial'):'Viagem',lida_em:base?.lida_em,origem:'declaracao',
      leitura_id:v.base_leitura_id||'—',hash_evidencia:'ver coletas abaixo',resumo:r?{minima_c:r.minima_c,maxima_c:r.maxima_c,abaixo:r.pontos_fora_da_faixa,acima:0}:null};
    const panel=temperaturePanel(dados,async(format,control,error)=>{
      control.disabled=true; error.textContent='';
      try { const blob=await api.request(`${path}?formato=${format}`,{download:true}); if(ticket!==view)return;
        const url=URL.createObjectURL(blob),a=document.createElement('a'); a.href=url; a.download=`ThermoTrace-viagem-${session}.${format}`; a.click(); setTimeout(()=>URL.revokeObjectURL(url),1000); }
      catch(e){ if(ticket===view)showError(e,error); } finally { control.disabled=false; }
    },'Viagem consolidada');
    const extra=el('div',undefined,'stack');
    if(r) extra.append(values([['Período',`${date(r.primeiro_instante)} a ${date(r.ultimo_instante)}`],['Na faixa',`${r.percentual_na_faixa}%`],['Tempo fora da faixa',`${Math.round(r.tempo_fora_nominal_s/60)} min (nominal)`],['Coletas conferidas',String(r.coletas_consideradas)]]));
    extra.append(el('h4','Excursões'),v.excursoes.length?table(['Tipo','Início','Fim','Duração','Extremo'],v.excursoes.map(e=>[e.tipo==='abaixo'?'Abaixo da faixa':'Acima da faixa',date(e.inicio),date(e.fim),`${Math.round(e.duracao_nominal_s/60)} min`,`${Number(e.extremo_c).toLocaleString('pt-BR')} °C`])):el('p','Nenhuma medição fora da faixa.','muted'));
    extra.append(el('h4','Coletas consideradas'),table(['Ordem','Tipo','Lida em','Medições','Uso'],v.coletas.map(c=>[c.ordem_recebimento,c.tipo,date(c.lida_em),c.medicoes,c.leitura_id===v.base_leitura_id?'Base da viagem':c.utilizavel?'Conferida contra a base':'Sem série'])));
    if(v.divergencias.length) extra.append(el('h4','Divergências entre coletas'),table(['Índice','Base','Outra coleta'],v.divergencias.map(x=>[x.indice,x.base_c??'—',x.coleta_c])));
    panel.append(extra); return panel;
  });
}

function temperatures(session,id,target,trigger,ticket) {
  const path=`/sessoes/${session}/leituras/${id}/temperaturas`;
  return action(target,trigger,ticket,async()=>{
    const data=await api.request(path);
    return temperaturePanel(data,async(format,control,error)=>{
      control.disabled=true; error.textContent='';
      try {
        const blob=await api.request(`${path}?formato=${format}`,{download:true});
        if(ticket!==view)return;
        const url=URL.createObjectURL(blob),a=document.createElement('a');
        a.href=url; a.download=`ThermoTrace-${id}.${format}`; a.click();
        setTimeout(()=>URL.revokeObjectURL(url),1000);
      } catch(e) { if(ticket===view)showError(e,error); }
      finally { control.disabled=false; }
    });
  });
}

login();
