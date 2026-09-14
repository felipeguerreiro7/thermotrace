import test from 'node:test';
import assert from 'node:assert/strict';
import {geometry} from '../app/portal/assets/chart.mjs';
const p=t=>({temperatura_c:t,instante:'2026-09-14T12:00:00Z'});
test('a faixa permanece visível mesmo numa série constante',()=>{
  const g=geometry([p(5),p(5)],{min_c:2,max_c:8});assert.ok(g.low<2&&g.high>8);assert.equal(g.x(0),75);assert.equal(g.x(1),865);
});
test('um ponto usa o centro e não produz divisão por zero',()=>{
  const g=geometry([p(-29.8)],{min_c:2,max_c:8});assert.equal(g.x(0),470);assert.ok(g.low<-29.8);assert.ok(!g.line.includes('NaN'));
});
test('ponto inválido é recusado, não filtrado silenciosamente',()=>{
  assert.throws(()=>geometry([p(5),p(NaN)],{min_c:2,max_c:8}));assert.throws(()=>geometry([p(5)],{min_c:8,max_c:2}));
});
test('histórico completo mantém todos os pontos',()=>{
  const g=geometry(Array.from({length:648},()=>p(5)),{min_c:2,max_c:8});assert.equal(g.line.split(' ').length,648);
});
