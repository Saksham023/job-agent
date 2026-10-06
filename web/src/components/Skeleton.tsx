export function CardSkeleton() {
  return (
    <div className="glass relative overflow-hidden rounded-2xl p-5">
      <div className="shimmer absolute inset-0" />
      <div className="flex items-start gap-3">
        <div className="h-11 w-11 rounded-xl bg-slate-200 dark:bg-white/10" />
        <div className="flex-1 space-y-2.5">
          <div className="h-3 w-24 rounded bg-slate-200 dark:bg-white/10" />
          <div className="h-4 w-4/5 rounded bg-slate-200 dark:bg-white/10" />
          <div className="h-3 w-2/5 rounded bg-slate-200 dark:bg-white/10" />
        </div>
      </div>
      <div className="mt-5 flex gap-2">
        {[64, 52, 76].map((w) => (
          <div key={w} className="h-6 rounded-full bg-slate-200 dark:bg-white/10" style={{ width: w }} />
        ))}
      </div>
    </div>
  )
}
