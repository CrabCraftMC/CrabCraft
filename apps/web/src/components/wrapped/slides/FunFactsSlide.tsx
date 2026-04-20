import type { WrappedData } from "../WrappedContainer";

const FACTS = [
    { key: "jumps", label: "Jumps" },
    { key: "animals_bred", label: "Animals Bred" },
    { key: "fish_caught", label: "Fish Caught" },
    { key: "villagers_traded", label: "Trades" },
    { key: "enchantments", label: "Enchants" },
    { key: "times_slept", label: "Times Slept" },
    { key: "player_kills", label: "PvP Kills" },
] as const;

export default function FunFactsSlide({ data }: { data: WrappedData }) {
    const facts = FACTS
        .map(f => ({ ...f, value: (data.stats as any)[f.key] as number }))
        .filter(f => f.value > 0);

    return (
        <div className="text-white">
            <p className="text-white/60 text-xs uppercase tracking-widest mb-4">Fun Facts</p>

            <div className="grid grid-cols-2 gap-2">
                {facts.map(f => (
                    <div key={f.key} className="bg-white/10 rounded-xl p-3 text-center">
                        <p className="font-bold font-mc text-lg">{f.value.toLocaleString()}</p>
                        <p className="text-white/60 text-[10px] uppercase">{f.label}</p>
                    </div>
                ))}
            </div>
        </div>
    );
}
