import type { AppConfig } from "@/lib/types";

type ModelDropdownProps = {
  label: string;
  value: string;
  options: AppConfig["availableModels"];
  disabled?: boolean;
  isOpen: boolean;
  onToggle: () => void;
  onSelect: (modelId: string) => void;
};

export function ModelDropdown({
  label,
  value,
  options,
  disabled = false,
  isOpen,
  onToggle,
  onSelect,
}: ModelDropdownProps) {
  const selectedOption =
    options.find((model) => model.id === value) ??
    options[0] ??
    null;

  return (
    <div className="space-y-1.5">
      <label className="text-[11px] uppercase tracking-[0.18em] text-slate-400">
        {label}
      </label>
      <div className="relative" data-model-dropdown>
        <button
          type="button"
          onClick={onToggle}
          disabled={disabled}
          className="flex w-full items-center justify-between gap-2 rounded-lg border border-white/10 bg-[rgba(3,9,24,0.9)] px-3 py-2 text-left text-sm text-white shadow-[inset_0_0_0_1px_rgba(148,163,184,0.05)] outline-none transition duration-200 hover:border-sky-300/26 hover:bg-[rgba(7,15,36,0.95)] focus-visible:ring-2 focus-visible:ring-sky-300/35 disabled:cursor-not-allowed disabled:opacity-60"
          aria-haspopup="listbox"
          aria-expanded={isOpen}
        >
          <span className="truncate">{selectedOption?.label ?? "Selecione um modelo"}</span>
          <svg
            viewBox="0 0 20 20"
            fill="none"
            className={`h-4 w-4 shrink-0 text-slate-400 transition ${isOpen ? "rotate-180" : ""}`}
            aria-hidden="true"
          >
            <path
              d="M5 7.5L10 12.5L15 7.5"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </button>

        {isOpen && !disabled ? (
          <div
            role="listbox"
            className="scroll-shell absolute z-40 mt-2 max-h-60 w-full overflow-y-auto rounded-lg border border-white/12 bg-[rgba(4,10,27,0.98)] p-1.5 shadow-[inset_0_0_0_1px_rgba(148,163,184,0.08),0_24px_48px_rgba(2,6,23,0.62)]"
          >
            {options.map((model) => {
              const isSelected = model.id === value;
              return (
                <button
                  key={`${label}-${model.id}`}
                  type="button"
                  role="option"
                  aria-selected={isSelected}
                  onClick={() => onSelect(model.id)}
                  className={`w-full rounded-md px-2.5 py-2 text-left transition ${
                    isSelected
                      ? "border border-sky-300/30 bg-sky-400/12 text-sky-100"
                      : "border border-transparent text-slate-200 hover:bg-white/6"
                  }`}
                >
                  <p className="truncate text-sm font-medium">{model.label}</p>
                  <p className="mt-0.5 text-[11px] leading-4 text-slate-400">
                    {model.description}
                  </p>
                </button>
              );
            })}
          </div>
        ) : null}
      </div>
    </div>
  );
}
