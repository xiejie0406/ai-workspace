import { Link } from '@/host/router'

export default function StartBanner({ label, to }: { label: string; to: string }) {
  return (
    <section className="mx-auto max-w-5xl px-6 pb-20">
      <div className="bg-ink rounded-panel relative isolate overflow-hidden px-8 py-14 text-center">
        <div
          className="absolute inset-0 bg-[radial-gradient(52%_60%_at_50%_-10%,rgb(217_255_110/0.22),transparent_70%)]"
          aria-hidden
        />

        <div className="relative">
          <h2 className="text-2xl font-semibold tracking-tight text-white sm:text-3xl">
            把商品图交给智能体
          </h2>
          <p className="mx-auto mt-3 max-w-md text-sm leading-relaxed text-white/65">
            从创作页描述需求，选择候选图，再使用画布和工具完成商品物料。
          </p>

          <Link
            to={to}
            className="bg-accent text-ink rounded-control mt-8 inline-flex px-6 py-3 text-sm font-medium transition-opacity hover:opacity-90"
          >
            {label}
          </Link>
        </div>
      </div>
    </section>
  )
}
