import clsx from 'clsx'
import { companyGradient, initials } from '../lib/format'

/** A company monogram on its own stable gradient. */
export function Avatar({ slug, name, size = 'md' }: { slug: string; name: string; size?: 'sm' | 'md' | 'lg' }) {
  return (
    <div
      className={clsx(
        'grid shrink-0 place-items-center rounded-xl bg-gradient-to-br font-display font-semibold text-white shadow-lg shadow-black/10',
        companyGradient(slug),
        size === 'sm' && 'h-7 w-7 text-[11px]',
        size === 'md' && 'h-11 w-11 text-sm',
        size === 'lg' && 'h-14 w-14 text-lg',
      )}
      aria-hidden
    >
      {initials(name)}
    </div>
  )
}
