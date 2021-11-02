package constellation.router

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.util._

import constellation._

class IngressUnit(
  cParam: ChannelParams,
  outParams: Seq[ChannelParams],
  egressParams: Seq[ChannelParams],
  combineRCVA: Boolean,
  combineSAST: Boolean,
  allocTable: (Int, Int, Int, Int, Int) => Boolean,
)
  (implicit p: Parameters) extends AbstractInputUnit(cParam, outParams, egressParams, allocTable)(p) {

  require(isIngressChannel)
  require(nVirtualChannels == 1)

  val io = IO(new AbstractInputUnitIO(cParam, outParams, egressParams) {
    val in = Flipped(Decoupled(new Flit(cParam)))
  })

  val route_buffer = Module(new Queue(new Flit(cParam), 2))
  val route_q = Module(new Queue(new RouteComputerResp(cParam, outParams, egressParams), 2,
    flow=combineRCVA))

  route_buffer.io.enq.bits := io.in.bits
  io.router_req.bits.src_virt_id := 0.U
  io.router_req.bits.src_vnet_id := io.in.bits.vnet_id
  io.router_req.bits.dest_id := egressIdToDestId(io.in.bits.egress_id)

  val out_is_in = egressIdToDestId(io.in.bits.egress_id) === nodeId.U
  route_buffer.io.enq.valid := io.in.valid && (
    io.router_req.ready || !io.in.bits.head || (out_is_in && !io.router_resp.valid))
  io.router_req.valid := io.in.valid && route_buffer.io.enq.ready && io.in.bits.head && !out_is_in
  io.in.ready := route_buffer.io.enq.ready && (
    io.router_req.ready || !io.in.bits.head || (out_is_in && !io.router_resp.valid))

  route_q.io.enq.valid := io.router_resp.valid
  route_q.io.enq.bits := io.router_resp.bits
  when (io.in.fire() && io.in.bits.head && out_is_in) {
    route_q.io.enq.valid := true.B
    route_q.io.enq.bits.src_virt_id := 0.U
    route_q.io.enq.bits.vc_sel.foreach(_.foreach(_ := false.B))
    val term_id = egressIdToEgressChannelId(io.in.bits.egress_id)
    for (o <- 0 until nEgress) {
      when (term_id === o.U) {
        route_q.io.enq.bits.vc_sel(o+nOutputs)(0) := true.B
      }
    }
  }
  assert(!(route_q.io.enq.valid && !route_q.io.enq.ready))

  val vcalloc_buffer = Module(new Queue(new Flit(cParam), 2))
  val vcalloc_q = Module(new Queue(new VCAllocResp(cParam, outParams, egressParams),
    1, pipe=true))

  vcalloc_buffer.io.enq.bits := route_buffer.io.deq.bits

  io.vcalloc_req.bits.in_virt_channel := 0.U
  io.vcalloc_req.bits.vc_sel := route_q.io.deq.bits.vc_sel

  val head = route_buffer.io.deq.bits.head
  val tail = route_buffer.io.deq.bits.tail
  vcalloc_buffer.io.enq.valid := (route_buffer.io.deq.valid &&
    (route_q.io.deq.valid || !head) &&
    (io.vcalloc_req.ready || !head)
  )
  io.vcalloc_req.valid := (route_buffer.io.deq.valid && route_q.io.deq.valid &&
    head && vcalloc_buffer.io.enq.ready && vcalloc_q.io.enq.ready)
  route_buffer.io.deq.ready := (vcalloc_buffer.io.enq.ready &&
    (route_q.io.deq.valid || !head) &&
    (io.vcalloc_req.ready || !head) &&
    (vcalloc_q.io.enq.ready || !head))
  route_q.io.deq.ready := (route_buffer.io.deq.fire() && tail)


  vcalloc_q.io.enq.valid := io.vcalloc_resp.valid
  vcalloc_q.io.enq.bits := io.vcalloc_resp.bits
  assert(!(vcalloc_q.io.enq.valid && !vcalloc_q.io.enq.ready))

  io.salloc_req(0).bits.vc_sel := vcalloc_q.io.deq.bits.vc_sel
  io.salloc_req(0).bits.tail := vcalloc_buffer.io.deq.bits.tail

  val c = (vcalloc_q.io.deq.bits.vc_sel.asUInt & io.out_credit_available.asUInt) =/= 0.U
  val vcalloc_tail = vcalloc_buffer.io.deq.bits.tail
  io.salloc_req(0).valid := vcalloc_buffer.io.deq.valid && vcalloc_q.io.deq.valid && c
  vcalloc_buffer.io.deq.ready := io.salloc_req(0).ready && vcalloc_q.io.deq.valid && c
  vcalloc_q.io.deq.ready := vcalloc_tail && vcalloc_buffer.io.deq.fire()

  val out_bundle = if (combineSAST) {
    Wire(Valid(new SwitchBundle(outParams, egressParams)))
  } else {
    Reg(Valid(new SwitchBundle(outParams, egressParams)))
  }
  io.out := out_bundle

  out_bundle.valid := vcalloc_buffer.io.deq.fire()
  out_bundle.bits.flit := vcalloc_buffer.io.deq.bits
  val out_channel_oh = vcalloc_q.io.deq.bits.vc_sel.map(_.reduce(_||_))
  out_bundle.bits.out_virt_channel := Mux1H(out_channel_oh, vcalloc_q.io.deq.bits.vc_sel.map(v => OHToUInt(v)))
  out_bundle.bits.out_channel_oh := VecInit(out_channel_oh)

  io.debug.va_stall := io.vcalloc_req.valid && !io.vcalloc_req.ready
  io.debug.sa_stall := io.salloc_req(0).valid && !io.salloc_req(0).ready
}