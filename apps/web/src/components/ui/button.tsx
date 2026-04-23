import * as React from "react";
import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../../lib/utils";

const buttonVariants = cva(
  "inline-flex items-center justify-center whitespace-nowrap rounded-lg text-sm font-medium transition-all duration-200 cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-orange-400 focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50",
  {
    variants: {
      variant: {
        default:
          "bg-orange-500 text-white hover:bg-orange-600 hover:shadow-lg hover:shadow-orange-500/25 active:scale-95",
        destructive:
          "bg-red-500 text-white hover:bg-red-600 hover:shadow-lg hover:shadow-red-500/25 active:scale-95",
        outline:
          "border-2 border-orange-400 text-orange-400 hover:bg-orange-400 hover:text-white hover:shadow-lg hover:shadow-orange-400/25 active:scale-95",
        secondary:
          "bg-gray-100 dark:bg-[#2a221b] text-gray-900 dark:text-gray-100 hover:bg-gray-200 dark:hover:bg-[#3d3028] hover:shadow-md active:scale-95",
        ghost:
          "text-gray-600 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-[#2a221b] hover:text-gray-900 dark:hover:text-gray-100 active:scale-95",
        dark: "text-white hover:bg-white/10 hover:text-white active:scale-95",
        minecraft:
          "bg-gradient-to-r from-orange-500 to-orange-600 text-white hover:from-orange-600 hover:to-orange-700 hover:shadow-xl  active:scale-95 font-bold",
        cubely:
          "text-white hover:bg-white/10 border-2 border-white hover:border-orange-400 hover:text-orange-400 transition-all duration-300 active:scale-95",
      },
      size: {
        default: "h-10 px-4 py-2",
        sm: "h-8 rounded-md px-3 text-xs",
        lg: "h-12 rounded-lg px-8 text-lg",
        xl: "h-14 rounded-xl px-12 text-xl",
        icon: "h-10 w-10",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean;
}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : "button";
    return (
      <Comp
        className={cn(buttonVariants({ variant, size, className }))}
        ref={ref}
        {...props}
      />
    );
  }
);
Button.displayName = "Button";

export { Button, buttonVariants };
